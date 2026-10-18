package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.LlmUsageRollupService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentCodec;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Actual bearer producers, scoped SQL and independently projected key fields. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InstitutionLlmAuthorizationTest {
    @Autowired private MockMvc mvc;
    @Autowired private tools.jackson.databind.ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private OrgRepository orgs;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private LlmUsageRollupService rollup;
    private Org first;
    private Org second;
    private Workspace one;
    private Workspace two;
    private User owner;

    @BeforeEach
    void syntheticRows() {
        String suffix = UUID.randomUUID().toString();
        first = orgs.save(new Org("LLM first " + suffix, null));
        second = orgs.save(new Org("LLM second " + suffix, null));
        one = workspaces.save(new Workspace(WorkspaceKind.PROJECT, "LLM one " + suffix, null));
        two = workspaces.save(new Workspace(WorkspaceKind.PROJECT, "LLM two " + suffix, null));
        owner = user(UserRole.USER);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", one.getId(), owner.getId());
    }

    @Test
    void metadataScopeFiltersBeforePageAndCountAndDoesNotExposeFinanceOrUsage() throws Exception {
        UUID visible = key(first, one, "visible"); key(first, two, "other workspace"); key(second, one, "other institution");
        User reader = selected(first, Set.of(InstitutionWork.LLM_METADATA_READ), one);
        String body = request(reader, "/api/v1/admin/llm/keys?size=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(visible.toString()))
                .andExpect(jsonPath("$.content[0].financeVisible").value(false)).andExpect(jsonPath("$.content[0].creditLimit").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("other institution", "other workspace");
        request(reader, "/api/v1/admin/llm/keys/" + visible + "/usage").andExpect(status().isNotFound());
    }

    @Test
    void observationAndIndividualFinanceAreIndependentForTheSameDisplayRole() throws Exception {
        UUID key = key(first, one, "independent");
        User observer = selected(first, Set.of(InstitutionWork.LLM_OBSERVE_SUPPORT_READ), one);
        User costReader = selected(first, Set.of(InstitutionWork.LLM_KEY_COST_READ), one);
        request(observer, "/api/v1/admin/llm/keys/" + key + "/usage").andExpect(status().isOk())
                .andExpect(jsonPath("$.financeVisible").value(false)).andExpect(jsonPath("$.costPoints").isEmpty())
                .andExpect(jsonPath("$.trend.budget.creditLimit").isEmpty());
        request(costReader, "/api/v1/admin/llm/keys/" + key + "/usage").andExpect(status().isOk())
                .andExpect(jsonPath("$.financeVisible").value(true)).andExpect(jsonPath("$.trend.points").isEmpty())
                .andExpect(jsonPath("$.trend.budget.creditLimit").value(1)).andExpect(jsonPath("$.trend.budget.todayTokens").isEmpty())
                .andExpect(jsonPath("$.endpointKinds").isEmpty());
        request(observer, "/api/v1/admin/llm/usage?orgId=" + first.getPublicId()).andExpect(status().isForbidden());
    }

    @Test
    void tokenOnlyPatchPreservesAllHiddenMonetaryFieldsAndNonchatFeatures() throws Exception {
        UUID key = key(first, one, "token patch");
        User operator = selected(first, Set.of(InstitutionWork.LLM_TOKEN_OPERATE), one);
        patch(operator, key, "{\"rpm\":71,\"chatEnabled\":true}").andExpect(status().isOk())
                .andExpect(jsonPath("$.rpm").value(71)).andExpect(jsonPath("$.financeVisible").value(false))
                .andExpect(jsonPath("$.observationVisible").value(false))
                .andExpect(jsonPath("$.creditLimit").isEmpty()).andExpect(jsonPath("$.passthroughEndpoints").isEmpty());
        assertThat(jdbc.queryForObject("select credit_limit from llm_api_keys where public_id=?", java.math.BigDecimal.class, key)).isEqualByComparingTo("1.00");
        assertThat(jdbc.queryForObject("select credit_denied_models::text from llm_api_keys where public_id=?", String.class, key)).contains("vendor/blocked");
        assertThat(jdbc.queryForObject("select passthrough_endpoints::text from llm_api_keys where public_id=?", String.class, key)).contains("images", "chat");
        patch(operator, key, "{\"creditLimit\":2}").andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select credit_limit from llm_api_keys where public_id=?", java.math.BigDecimal.class, key)).isEqualByComparingTo("1.00");
    }

    @Test
    void explicitNullAndContradictoryChatPatchRejectWithoutChangingTheRow() throws Exception {
        UUID key = key(first, one, "chat conflict"); User operator = selected(first, Set.of(InstitutionWork.LLM_TOKEN_OPERATE), one);
        patch(operator, key, "{\"chatEnabled\":true,\"passthroughEndpoints\":[\"images\"]}").andExpect(status().isUnprocessableContent());
        patch(operator, key, "{\"chatEnabled\":null}").andExpect(status().isUnprocessableContent());
        patch(operator, key, "{\"creditLimit\":null}").andExpect(status().isUnprocessableContent());
        assertThat(jdbc.queryForObject("select passthrough_endpoints::text from llm_api_keys where public_id=?", String.class, key)).contains("images", "chat");
    }

    @Test
    void fullForeignIndividualCostDoesNotOpenForeignAggregateOrAccountFinance() throws Exception {
        UUID foreign = key(second, one, "foreign");
        User full = user(UserRole.ORG_ADMIN); grant(full, first, InstitutionAssignment.full(full.getPublicId(), first.getPublicId(), 0));
        request(full, "/api/v1/admin/llm/keys/" + foreign).andExpect(status().isOk())
                .andExpect(jsonPath("$.financeVisible").value(true)).andExpect(jsonPath("$.creditLimit").value(1));
        request(full, "/api/v1/admin/llm/usage?orgId=" + second.getPublicId()).andExpect(status().isNotFound());
        UUID account = jdbc.queryForObject("select a.public_id from openrouter_accounts a join llm_api_keys k on k.openrouter_account_id=a.id where k.public_id=?", UUID.class, foreign);
        request(full, "/api/v1/admin/llm/accounts/" + account).andExpect(status().isNotFound());
    }

    @Test
    void scopedMetricsUseActualInstitutionAndWorkspaceBeforeAggregation() throws Exception {
        UUID visible = key(first, one, "metric one"); UUID hidden = key(first, two, "metric two");
        event(visible, 13); event(hidden, 900);
        User observer = selected(first, Set.of(InstitutionWork.LLM_OBSERVE), one);
        request(observer, "/api/v1/admin/llm/metrics?orgId=" + first.getPublicId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(1));
        rollup.refresh();
        request(observer, "/api/v1/admin/llm/usage?orgId=" + first.getPublicId() + "&top=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.totalItems").value(1)).andExpect(jsonPath("$.consumers.items[0].inputTokens").value(13))
                .andExpect(jsonPath("$.consumers.items[0].pricedRequests").isEmpty())
                .andExpect(jsonPath("$.quality.creditMetersTotal").isEmpty());
    }

    @Test
    void hiddenMoneyCannotDetermineLimitReviewEligibilityOrRank() throws Exception {
        UUID monetaryOnly = key(first, one, "money only"); UUID token = key(first, one, "token limited");
        jdbc.update("update llm_api_keys set daily_tokens=null,rpm=null,tpm=null,concurrency=null where public_id=?", monetaryOnly);
        User observer = selected(first, Set.of(InstitutionWork.LLM_OBSERVE), one);
        request(observer, "/api/v1/admin/llm/usage?orgId=" + first.getPublicId() + "&top=1").andExpect(status().isOk())
                .andExpect(jsonPath("$.limitReview.totalItems").value(1)).andExpect(jsonPath("$.limitReview.items[0].keyId").value(token.toString()))
                .andExpect(jsonPath("$.limitReview.items[0].creditLimit").isEmpty()).andExpect(jsonPath("$.limitReview.items[0].financeVisible").value(false));
    }

    @Test
    void paidApprovalNeedsBudgetFromTheSameDecisionSourceAndRejectDoesNot() throws Exception {
        UUID request = requestRow(first, one, "paid approval");
        User decider = selected(first, Set.of(InstitutionWork.LLM_REQUEST_DECIDE), one);
        mvc.perform(post("/api/v1/admin/requests/" + request + "/approve").header("Authorization", bearer(decider))
                .contentType(MediaType.APPLICATION_JSON).content("{\"llmKey\":{\"grantedCreditLimit\":1}}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select status::text from requests where public_id=?", String.class, request)).isEqualTo("SUBMITTED");
        mvc.perform(post("/api/v1/admin/requests/" + request + "/reject").header("Authorization", bearer(decider))
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"거절 대조\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void rosterLookupRequiresItsExplicitKindAndWorkspaceScope() throws Exception {
        User roster = selected(first, Set.of(InstitutionWork.LLM_ROSTER_ISSUE), one);
        String url = "/api/v1/workspaces/" + one.getPublicId() + "/roster/resolve";
        mvc.perform(post(url).header("Authorization", bearer(roster)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orgId\":\"" + first.getPublicId() + "\",\"studentNos\":[\"202600001\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).header("Authorization", bearer(roster)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orgId\":\"" + first.getPublicId() + "\",\"type\":\"DOMAIN\",\"studentNos\":[\"202600001\"]}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post(url).header("Authorization", bearer(roster)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orgId\":\"" + first.getPublicId() + "\",\"type\":\"LLM_API_KEY\",\"studentNos\":[\"202600001\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void inconsistentWholeAuthorityNeverRecoversFromItsDisplayRole() throws Exception {
        UUID key = key(first, one, "unconverted"); User actor = user(UserRole.ORG_ADMIN);
        grant(actor, first, InstitutionAssignment.full(actor.getPublicId(), first.getPublicId(), 0));
        jdbc.update("update users set role='ORG_MANAGER' where id=?", actor.getId());
        request(actor, "/api/v1/admin/llm/keys/" + key).andExpect(status().isNotFound());
        request(actor, "/api/v1/admin/llm/keys").andExpect(status().isForbidden());
        patch(actor, key, "{\"rpm\":81}").andExpect(status().isNotFound());
    }

    @Test
    void rosterOnlyCanIssueANewRosterWithoutDecidingExistingRequests() throws Exception {
        User issuer = selected(first, Set.of(InstitutionWork.LLM_ROSTER_ISSUE), one);
        assertThat(jdbc.queryForObject("select count(*) from workspace_members where workspace_id=? and user_id=?", Long.class, one.getId(), issuer.getId())).isZero();
        mvc.perform(post("/api/v1/requests").header("Authorization", bearer(issuer)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(rosterBody(Map.of()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.review.reviewerId").value(issuer.getPublicId().toString()))
                .andExpect(jsonPath("$.recipients[0].status").value("QUEUED"));
        UUID other = requestRow(first, one, "existing separate request");
        mvc.perform(post("/api/v1/admin/requests/" + other + "/approve").header("Authorization", bearer(issuer))
                .contentType(MediaType.APPLICATION_JSON).content("{\"llmKey\":{}}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void paidRosterRequiresBudgetFromItsOwnCompleteSource() throws Exception {
        User issuer = selected(first, Set.of(InstitutionWork.LLM_ROSTER_ISSUE), one);
        Map<String, Object> grant = Map.of("grantedCreditDeniedModels", java.util.List.of("vendor/blocked"));
        mvc.perform(post("/api/v1/requests").header("Authorization", bearer(issuer)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(rosterBody(grant)))).andExpect(status().isForbidden());
        grant(issuer, second, InstitutionAssignment.selected(issuer.getPublicId(), second.getPublicId(), Set.of(InstitutionWork.LLM_KEY_BUDGET),
                Map.of(InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.listed(Set.of(one.getPublicId()))), 0));
        mvc.perform(post("/api/v1/requests").header("Authorization", bearer(issuer)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(rosterBody(grant)))).andExpect(status().isForbidden());
        replace(issuer, first, InstitutionAssignment.selected(issuer.getPublicId(), first.getPublicId(),
                Set.of(InstitutionWork.LLM_ROSTER_ISSUE, InstitutionWork.LLM_KEY_BUDGET),
                Map.of(InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.listed(Set.of(one.getPublicId()))), 0));
        mvc.perform(post("/api/v1/requests").header("Authorization", bearer(issuer)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(rosterBody(grant)))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.llmKey.grantedCreditDeniedModels[0]").value("vendor/blocked"));
    }

    @Test
    void personalOwnerCanStillSubmitAnOrdinaryRosterWithoutAnInstitutionGrant() throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(rosterBody(Map.of())); body.remove("approval");
        mvc.perform(post("/api/v1/requests").header("Authorization", bearer(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("SUBMITTED"));
        assertThat(jdbc.queryForObject("select count(*) from user_org_roles where user_id=?", Long.class, owner.getId())).isZero();
    }

    @Test
    void listedCurrentServiceReferencesNeedALiveActualLlmRelationWhileAllAllowsZeroResources() throws Exception {
        User listed = selected(first, Set.of(InstitutionWork.LLM_OBSERVE), one);
        request(listed, "/api/v1/admin/llm/status?orgId=" + first.getPublicId()).andExpect(status().isForbidden());
        requestRow(first, one, "actual LLM relation");
        request(listed, "/api/v1/admin/llm/status?orgId=" + first.getPublicId()).andExpect(status().isOk());
        jdbc.update("update workspaces set deleted_at=now(),deleted_by=? where id=?", owner.getId(), one.getId());
        request(listed, "/api/v1/admin/llm/status?orgId=" + first.getPublicId()).andExpect(status().isForbidden());
        User all = user(UserRole.ORG_VIEWER);
        grant(all, first, InstitutionAssignment.selected(all.getPublicId(), first.getPublicId(), Set.of(InstitutionWork.LLM_OBSERVE),
                Map.of(InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.all()), 0));
        request(all, "/api/v1/admin/llm/status?orgId=" + first.getPublicId()).andExpect(status().isOk());
    }

    @Test
    void consumerMoneyIsUnavailableInsteadOfAPartialSumWhenAnObservedSourceLacksCost() throws Exception {
        UUID paid = key(first, one, "complete money source"); UUID hidden = key(second, one, "observation source");
        event(paid, 50); event(hidden, 60); rollup.refresh();
        User viewer = selected(first, Set.of(InstitutionWork.LLM_OBSERVE, InstitutionWork.LLM_KEY_COST_READ), one);
        grant(viewer, second, InstitutionAssignment.selected(viewer.getPublicId(), second.getPublicId(), Set.of(InstitutionWork.LLM_OBSERVE),
                Map.of(InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.listed(Set.of(one.getPublicId()))), 0));
        request(viewer, "/api/v1/admin/llm/usage").andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.totalItems").value(1)).andExpect(jsonPath("$.consumers.items[0].inputTokens").value(110))
                .andExpect(jsonPath("$.consumers.items[0].pricedRequests").isEmpty()).andExpect(jsonPath("$.quality.creditMetersTotal").isEmpty());
        request(viewer, "/api/v1/admin/llm/usage?orgId=" + first.getPublicId()).andExpect(status().isOk())
                .andExpect(jsonPath("$.consumers.items[0].pricedRequests").value(0)).andExpect(jsonPath("$.quality.creditMetersTotal").value(1));
    }

    @Test
    void anotherFullSourceSurvivesRevocationAndSelectedAllDoesNotKeepGlobalFinance() throws Exception {
        UUID target = key(first, one, "global finance source"); User full = user(UserRole.ORG_ADMIN);
        grant(full, first, InstitutionAssignment.full(full.getPublicId(), first.getPublicId(), 0));
        grant(full, second, InstitutionAssignment.full(full.getPublicId(), second.getPublicId(), 0)); String token = bearer(full);
        jdbc.update("delete from user_org_roles where user_id=? and org_id=?", full.getId(), first.getId());
        mvc.perform(get("/api/v1/admin/llm/keys/" + target).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.financeVisible").value(true));
        Set<InstitutionWork> all = java.util.Arrays.stream(InstitutionWork.values()).filter(InstitutionWork::selectable).collect(java.util.stream.Collectors.toSet());
        Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes = new java.util.EnumMap<>(InstitutionWorkGroup.class);
        for (InstitutionWork work : all) { if (work.group() != null) { scopes.put(work.group(), InstitutionWorkspaceScope.all()); } }
        replace(full, second, InstitutionAssignment.selected(full.getPublicId(), second.getPublicId(), all, scopes, 0));
        jdbc.update("update users set role='ORG_MANAGER' where id=?", full.getId());
        mvc.perform(get("/api/v1/admin/llm/keys/" + target).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.financeVisible").value(false)).andExpect(jsonPath("$.creditLimit").isEmpty());
        jdbc.update("delete from user_org_roles where user_id=?", full.getId()); jdbc.update("update users set role='USER' where id=?", full.getId());
        mvc.perform(get("/api/v1/admin/llm/keys/" + target).header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test
    void newPaidRosterAccountReferencesNeedNoPriorResourceOrMembershipAndDoNotOpenInventory() throws Exception {
        User issuer = selected(first, Set.of(InstitutionWork.LLM_ROSTER_ISSUE, InstitutionWork.LLM_KEY_BUDGET), one);
        UUID account = jdbc.queryForObject("insert into openrouter_accounts(org_id,name,created_by) values(?,'명단 참조 계정',?) returning public_id",
                UUID.class, first.getId(), owner.getId());
        String uri = "/api/v1/admin/llm/roster-account-references?orgId=" + first.getPublicId() + "&workspaceId=" + one.getPublicId();
        assertThat(jdbc.queryForObject("select count(*) from requests where workspace_id=?", Long.class, one.getId())).isZero();
        request(issuer, uri).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(account.toString()))
                .andExpect(jsonPath("$[0].eligibleForBinding").value(false)).andExpect(jsonPath("$[0].credits").doesNotExist())
                .andExpect(jsonPath("$[0].allocation").doesNotExist()).andExpect(jsonPath("$[0].activeCredential").doesNotExist());
        request(issuer, "/api/v1/admin/llm/accounts/" + account).andExpect(status().isNotFound());
        User unbudgeted = selected(first, Set.of(InstitutionWork.LLM_ROSTER_ISSUE), one);
        request(unbudgeted, uri).andExpect(status().isNotFound());
        request(issuer, "/api/v1/admin/llm/roster-account-references?orgId=" + first.getPublicId() + "&workspaceId=" + two.getPublicId()).andExpect(status().isNotFound());
        request(user(UserRole.SYS_MANAGER), uri).andExpect(status().isForbidden());
        request(user(UserRole.SYS_ADMIN), uri).andExpect(status().isOk());
    }

    @Test
    void invalidInstitutionContextRetainsItsRealPersonalWorkspaceOwnerRight() throws Exception {
        UUID key = key(first, one, "personal owner with inconsistent institution"); User actor = user(UserRole.ORG_ADMIN);
        grant(actor, first, InstitutionAssignment.full(actor.getPublicId(), first.getPublicId(), 0));
        jdbc.update("update users set role='ORG_MANAGER' where id=?", actor.getId());
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", one.getId(), actor.getId());
        request(actor, "/api/v1/admin/llm/keys/" + key).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/llm-keys/" + key + "/revoke").header("Authorization", bearer(actor))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select status::text from llm_api_keys where public_id=?", String.class, key)).isEqualTo("REVOKED");
    }

    @Test
    void budgetCommandReferenceDoesNotExposeObservedMoneyOrTokenUsage() throws Exception {
        UUID key = key(first, one, "budget-only current reference"); User budget = selected(first, Set.of(InstitutionWork.LLM_KEY_BUDGET), one);
        jdbc.update("update llm_api_keys set openrouter_usage=.75,openrouter_limit_remaining=.25,openrouter_usage_at=now() where public_id=?", key);
        request(budget, "/api/v1/admin/llm/keys/" + key).andExpect(status().isOk())
                .andExpect(jsonPath("$.financeVisible").value(false)).andExpect(jsonPath("$.budgetReferenceVisible").value(true))
                .andExpect(jsonPath("$.creditLimit").value(1)).andExpect(jsonPath("$.creditDeniedModels[0]").value("vendor/blocked"))
                .andExpect(jsonPath("$.creditUsage").isEmpty()).andExpect(jsonPath("$.creditLimitRemaining").isEmpty())
                .andExpect(jsonPath("$.creditUsageAt").isEmpty()).andExpect(jsonPath("$.creditAxisConnected").isEmpty())
                .andExpect(jsonPath("$.rpm").isEmpty()).andExpect(jsonPath("$.accountReferences[0].id").isNotEmpty());
        request(budget, "/api/v1/admin/llm/keys/" + key + "/usage").andExpect(status().isNotFound());
    }

    @Test
    void stateOnlyCommandReadsTheActualStateWithoutTokenOrFinancialConfiguration() throws Exception {
        UUID key = key(first, one, "state-only reference"); User operator = selected(first, Set.of(InstitutionWork.LLM_STATE_OPERATE), one);
        request(operator, "/api/v1/admin/llm/keys/" + key).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.chatEnabled").isEmpty()).andExpect(jsonPath("$.rpm").isEmpty())
                .andExpect(jsonPath("$.creditLimit").isEmpty()).andExpect(jsonPath("$.tokenReferenceVisible").value(false));
        mvc.perform(post("/api/v1/admin/llm/keys/" + key + "/suspend").header("Authorization", bearer(operator))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"상태 업무 대조\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUSPENDED")).andExpect(jsonPath("$.chatEnabled").isEmpty());
    }

    @Test
    void tokenOnlyCannotProbeHiddenCurrentBudgetWithAnUnchangedProperty() throws Exception {
        UUID key = key(first, one, "hidden money probe"); User token = selected(first, Set.of(InstitutionWork.LLM_TOKEN_OPERATE), one);
        patch(token, key, "{\"rpm\":71,\"creditLimit\":2}").andExpect(status().isForbidden());
        patch(token, key, "{\"rpm\":71,\"creditLimit\":1}").andExpect(status().isForbidden());
    }

    @Test
    void hiddenMoneyDoesNotChangeTokenOnlyBulkFingerprint() throws Exception {
        UUID key = key(first, one, "hidden money fingerprint"); User token = selected(first, Set.of(InstitutionWork.LLM_TOKEN_OPERATE), one);
        var before = bulkPreview(token, key); String fingerprint = before.get("items").get(0).get("fingerprint").asString();
        jdbc.update("update llm_api_keys set credit_limit=2,credit_denied_models='[\"vendor/changed\"]' where public_id=?", key);
        var after = bulkPreview(token, key);
        assertThat(after.get("items").get(0).get("fingerprint").asString()).isEqualTo(fingerprint);
        Map<String, Object> apply = new java.util.HashMap<>(bulkBody(key));
        apply.put("previewId", before.get("previewId").asString()); apply.put("operationId", UUID.randomUUID());
        apply.put("fingerprints", Map.of(key.toString(), fingerprint));
        mvc.perform(post("/api/v1/admin/bulk-changes").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(apply))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].result").value("APPLIED"));
        assertThat(jdbc.queryForObject("select credit_limit from llm_api_keys where public_id=?", java.math.BigDecimal.class, key)).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("select credit_denied_models::text from llm_api_keys where public_id=?", String.class, key)).contains("vendor/changed");
    }

    @Test
    void tokenOnlyBulkCurrentObservationDoesNotExposeBudgetConfiguration() throws Exception {
        UUID key = key(first, one, "hidden money current observation"); User token = selected(first, Set.of(InstitutionWork.LLM_TOKEN_OPERATE), one);
        var preview = bulkPreview(token, key); UUID operation = UUID.randomUUID();
        Map<String, Object> apply = new java.util.HashMap<>(bulkBody(key));
        apply.put("previewId", preview.get("previewId").asString()); apply.put("operationId", operation);
        apply.put("fingerprints", Map.of(key.toString(), preview.get("items").get(0).get("fingerprint").asString()));
        mvc.perform(post("/api/v1/admin/bulk-changes").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(apply))).andExpect(status().isOk());
        request(token, "/api/v1/admin/bulk-changes/operations/" + operation).andExpect(status().isOk())
                .andExpect(jsonPath("$.observations[0].values.rpm").value(71))
                .andExpect(jsonPath("$.observations[0].values.creditLimit").doesNotExist())
                .andExpect(jsonPath("$.observations[0].values.creditDeniedModels").doesNotExist())
                .andExpect(jsonPath("$.observations[0].values.passthroughEndpoints").doesNotExist());
    }

    private Map<String, Object> bulkBody(UUID key) {
        return Map.of("targetType", "LLM_KEY", "targetIds", java.util.List.of(key),
                "change", Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", Map.of("rpm", 71)));
    }
    private tools.jackson.databind.JsonNode bulkPreview(User actor, UUID key) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/admin/bulk-changes/preview").header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(bulkBody(key))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Map<String, Object> rosterBody(Map<String, Object> grant) {
        return Map.of("type", "LLM_API_KEY", "workspaceId", one.getPublicId(), "orgId", first.getPublicId(),
                "purpose", "명단 발급 합성", "reqIndefinite", true, "displayName", "새 명단 합성",
                "llmKey", Map.of("reqPassthroughEndpoints", java.util.List.of("chat")),
                "recipients", java.util.List.of(Map.of("userId", owner.getPublicId())), "approval", Map.of("llmKey", grant));
    }

    private User user(UserRole role) {
        User value = new User("llm-authority-" + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", "권한 시험");
        value.setRole(role); value.setStatus(UserStatus.ACTIVE); value.setEmailVerifiedAt(Instant.now()); return users.save(value);
    }
    @Test
    void aclOnlyAuthorityGetsASelectableKeyReferenceWithoutItsMetadataOrFinance() throws Exception {
        UUID key=key(first,one,"권한 관리 대상");
        User manager=selected(first,Set.of(InstitutionWork.LLM_ACL_SUPPORT_MANAGE),one);
        request(manager,"/api/v1/admin/llm/keys").andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(key.toString()))
                .andExpect(jsonPath("$.content[0].metadataVisible").value(false))
                .andExpect(jsonPath("$.content[0].financeVisible").value(false))
                .andExpect(jsonPath("$.content[0].allowedWorkIds[0]").value("llm.acl.support.manage"));
        request(manager,"/api/v1/admin/llm/keys/"+key).andExpect(status().isOk())
                .andExpect(jsonPath("$.metadataVisible").value(false)).andExpect(jsonPath("$.creditLimit").doesNotExist())
                .andExpect(jsonPath("$.rpm").doesNotExist()).andExpect(jsonPath("$.purpose").doesNotExist())
                .andExpect(jsonPath("$.allowedWorkIds.length()").value(1));
        request(manager,"/api/v1/admin/llm/keys/"+key+"/usage").andExpect(status().isNotFound());
    }

    @Test
    void requestActionReferencesDoNotCombineDecisionAndBudgetFromDifferentSources() throws Exception {
        UUID request=requestRow(first,one,"결정과 예산 출처");
        User decider=selected(first,Set.of(InstitutionWork.LLM_REQUEST_DECIDE),one);
        grant(decider,second,InstitutionAssignment.selected(decider.getPublicId(),second.getPublicId(),
                Set.of(InstitutionWork.LLM_KEY_BUDGET),Map.of(InstitutionWorkGroup.LLM,InstitutionWorkspaceScope.all()),0));
        request(decider,"/api/v1/admin/requests/"+request).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedWorkIds.length()").value(1))
                .andExpect(jsonPath("$.allowedWorkIds[0]").value("llm.request.decide"));
        replace(decider,first,InstitutionAssignment.selected(decider.getPublicId(),first.getPublicId(),
                Set.of(InstitutionWork.LLM_REQUEST_DECIDE,InstitutionWork.LLM_KEY_BUDGET),
                Map.of(InstitutionWorkGroup.LLM,InstitutionWorkspaceScope.listed(Set.of(one.getPublicId()))),0));
        request(decider,"/api/v1/admin/requests/"+request).andExpect(status().isOk())
                .andExpect(jsonPath("$.allowedWorkIds.length()").value(2))
                .andExpect(jsonPath("$.allowedWorkIds[0]").value("llm.key.budget"));
    }

    @Test
    void budgetBindingReferenceDoesNotRequireFinancialObservationAndPreservesTheUnboundStateCheck() throws Exception {
        UUID request=requestRow(first,one,"최초 결합 참조");
        long requestId=jdbc.queryForObject("select id from requests where public_id=?",Long.class,request);
        UUID key=jdbc.queryForObject("insert into llm_api_keys(workspace_id,org_id,request_id,name,status,credit_limit,created_by)"
                + " values(?,?,?,'최초 결합 참조','ACTIVE',0,?) returning public_id",UUID.class,one.getId(),first.getId(),requestId,owner.getId());
        User budget=selected(first,Set.of(InstitutionWork.LLM_KEY_BUDGET),one);
        request(budget,"/api/v1/admin/llm/keys/"+key).andExpect(status().isOk())
                .andExpect(jsonPath("$.initialAccountBindingAllowed").value(true)).andExpect(jsonPath("$.creditAxisConnected").isEmpty());
        long bindingAccount=jdbc.queryForObject("insert into openrouter_accounts(org_id,name,created_by) values(?,'결합된 계정',?) returning id",Long.class,first.getId(),owner.getId());
        jdbc.update("update llm_api_keys set openrouter_account_id=? where public_id=?",bindingAccount,key);
        request(budget,"/api/v1/admin/llm/keys/"+key).andExpect(status().isOk())
                .andExpect(jsonPath("$.initialAccountBindingAllowed").value(false)).andExpect(jsonPath("$.creditUsage").isEmpty());
        User token=selected(first,Set.of(InstitutionWork.LLM_TOKEN_OPERATE),one);
        request(token,"/api/v1/admin/llm/keys/"+key).andExpect(status().isOk()).andExpect(jsonPath("$.initialAccountBindingAllowed").isEmpty());
    }

    @Test
    void accountPolicyManagementGetsItsCurrentDefaultsWithoutCreditsAllocationOrCredentials() throws Exception {
        UUID policyAccount=jdbc.queryForObject("insert into openrouter_accounts(org_id,name,created_by) values(?,'기본 정책 참조',?) returning public_id",UUID.class,first.getId(),owner.getId());
        User manager=user(UserRole.ORG_MANAGER);
        grant(manager,first,InstitutionAssignment.selected(manager.getPublicId(),first.getPublicId(),Set.of(InstitutionWork.INSTITUTION_ACCOUNT_MANAGE),Map.of(),0));
        request(manager,"/api/v1/admin/llm/accounts/"+policyAccount).andExpect(status().isOk())
                .andExpect(jsonPath("$.policyReferenceVisible").value(true)).andExpect(jsonPath("$.defaultCreditAllowedModels").isArray())
                .andExpect(jsonPath("$.financeVisible").value(false)).andExpect(jsonPath("$.credits").isEmpty())
                .andExpect(jsonPath("$.allocation").isEmpty()).andExpect(jsonPath("$.activeCredential").isEmpty());
    }

    private User selected(Org org, Set<InstitutionWork> work, Workspace workspace) {
        User actor = user(work.stream().anyMatch(InstitutionWork::write) ? UserRole.ORG_MANAGER : UserRole.ORG_VIEWER);
        grant(actor, org, InstitutionAssignment.selected(actor.getPublicId(), org.getPublicId(), work,
                Map.of(InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.listed(Set.of(workspace.getPublicId()))), 0)); return actor;
    }
    private void grant(User actor, Org org, InstitutionAssignment assignment) {
        var encoded = new InstitutionAssignmentCodec().encode(assignment);
        jdbc.update("insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision) values(?,?,?::user_role,?,?::jsonb,nextval('institution_permission_revision_seq'))",
                actor.getId(), org.getId(), assignment.displayRole().name(), encoded.mode(), encoded.payload());
    }
    private void replace(User actor, Org org, InstitutionAssignment assignment) {
        var encoded = new InstitutionAssignmentCodec().encode(assignment);
        jdbc.update("update user_org_roles set role=?::user_role,permission_mode=?,permission_payload=?::jsonb,permission_revision=nextval('institution_permission_revision_seq') where user_id=? and org_id=?",
                assignment.displayRole().name(), encoded.mode(), encoded.payload(), actor.getId(), org.getId());
    }
    private UUID requestRow(Org org, Workspace workspace, String name) {
        long id = jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values('LLM_API_KEY',?,?,?,'권한 시험',?) returning id", Long.class,
                workspace.getId(), org.getId(), owner.getId(), name);
        jdbc.update("insert into llm_key_request_details(request_id,req_passthrough_endpoints) values(?,'[\"chat\"]')", id);
        return jdbc.queryForObject("select public_id from requests where id=?", UUID.class, id);
    }
    private UUID key(Org org, Workspace workspace, String name) {
        UUID request = requestRow(org, workspace, name);
        long requestId = jdbc.queryForObject("select id from requests where public_id=?", Long.class, request);
        long account = jdbc.queryForObject("insert into openrouter_accounts(org_id,name,created_by) values(?,?,?) returning id", Long.class, org.getId(), name + UUID.randomUUID(), owner.getId());
        return jdbc.queryForObject("""
                insert into llm_api_keys(workspace_id,org_id,request_id,name,purpose,status,rpm,tpm,concurrency,daily_tokens,
                    credit_limit,openrouter_account_id,credit_denied_models,passthrough_endpoints,created_by)
                values(?,?,?,?,'비밀 용도','ACTIVE',60,1000,4,10000,1,?,'["vendor/blocked"]','["chat","images"]',?) returning public_id
                """, UUID.class, workspace.getId(), org.getId(), requestId, name, account, owner.getId());
    }
    private void event(UUID key, int tokens) {
        jdbc.update("""
                insert into llm_usage_events(event_id,key_id,public_model_name,budget_axis,status,input_tokens,output_tokens,estimated,latency_ms,requested_at)
                select ?,id,'institution-test-model','TOKEN','OK',?,0,false,1,? from llm_api_keys where public_id=?
                """, UUID.randomUUID().toString(), tokens, Timestamp.from(Instant.now().minusSeconds(30)), key);
    }
    private String bearer(User actor) { return "Bearer " + jwt.createAccessToken(actor); }
    private ResultActions request(User actor, String uri) throws Exception { return mvc.perform(get(uri).header("Authorization", bearer(actor))); }
    private ResultActions patch(User actor, UUID key, String body) throws Exception {
        return mvc.perform(put("/api/v1/admin/llm/keys/" + key + "/limits").header("Authorization", bearer(actor))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
