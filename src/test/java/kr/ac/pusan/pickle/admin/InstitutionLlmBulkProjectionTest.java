package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Current key field admission also applies to fresh and durable bulk projections. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InstitutionLlmBulkProjectionTest {
    private static final String BASE = "/api/v1/admin/bulk-changes";
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private OrgRepository institutions;
    @Autowired private WorkspaceRepository workspaces;
    private Org institution;
    private Workspace workspace;
    private User actor;
    private UUID key;

    @BeforeEach
    void committedTargetsAndExplicitAuthority() {
        institution = institutions.save(new Org("Bulk projection " + UUID.randomUUID(), null));
        workspace = workspaces.save(new Workspace(WorkspaceKind.PROJECT, "Bulk projection " + UUID.randomUUID(), null));
        actor = new User("bulk-projection-" + UUID.randomUUID() + "@example.invalid", "{synthetic-no-login}", "합성 담당자");
        actor.setRole(UserRole.ORG_MANAGER); actor.setStatus(UserStatus.ACTIVE); actor.setEmailVerifiedAt(Instant.now());
        actor = users.save(actor);
        long request = jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) "
                + "values('LLM_API_KEY',?,?,?,'합성 검토','합성 키') returning id", Long.class, workspace.getId(), institution.getId(), actor.getId());
        long account = jdbc.queryForObject("insert into openrouter_accounts(org_id,name,created_by) values(?,?,?) returning id",
                Long.class, institution.getId(), "Synthetic " + UUID.randomUUID(), actor.getId());
        key = jdbc.queryForObject("""
                insert into llm_api_keys(workspace_id,org_id,request_id,name,purpose,status,rpm,tpm,concurrency,daily_tokens,
                    credit_limit,openrouter_account_id,credit_denied_models,passthrough_endpoints,created_by)
                values(?,?,?,'합성 키','숨긴 용도','ACTIVE',60,1000,4,10000,1,?,'["vendor/blocked"]','["chat","images"]',?) returning public_id
                """, UUID.class, workspace.getId(), institution.getId(), request, account, actor.getId());
        var encoded = new InstitutionAssignmentCodec().encode(assignment(Set.of(InstitutionWork.LLM_TOKEN_OPERATE)));
        jdbc.update("insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision) "
                + "values(?,?,'ORG_MANAGER',?,?::jsonb,nextval('institution_permission_revision_seq'))",
                actor.getId(), institution.getId(), encoded.mode(), encoded.payload());
    }

    @Test
    void tokenOnlyChatAdditionProjectsOnlyItsBooleanAndPreservesPaidFeatures() throws Exception {
        chatOnlyChange(false);
    }

    @Test
    void tokenOnlyChatRemovalProjectsOnlyItsBooleanAndPreservesPaidFeatures() throws Exception {
        chatOnlyChange(true);
    }

    private void chatOnlyChange(boolean enabled) throws Exception {
        jdbc.update("update llm_api_keys set passthrough_endpoints=?::jsonb where public_id=?",
                enabled ? "[\"chat\",\"images\"]" : "[\"images\"]", key);
        String token = bearer();
        Map<String, Object> body = body(Map.of("passthroughEndpoints", Map.of("op", enabled ? "REMOVE" : "ADD", "values", List.of("chat"))));
        JsonNode preview = postBody(token, BASE + "/preview", body);
        JsonNode applied = postBody(token, BASE, applyBody(body, preview, UUID.randomUUID()));
        assertThat(applied.get("items").get(0).get("result").asString()).isEqualTo("APPLIED");
        List<String> actual = strings(json.readTree(jdbc.queryForObject("select passthrough_endpoints::text from llm_api_keys where public_id=?", String.class, key)));
        assertThat(actual).contains("images");
        assertThat(actual.contains("chat")).isEqualTo(!enabled);
        assertThat(jdbc.queryForObject("select credit_limit from llm_api_keys where public_id=?", BigDecimal.class, key)).isEqualByComparingTo("1");
        assertAll(
                () -> assertChatFields(preview.get("items").get(0).get("fields"), enabled),
                () -> assertChatFields(applied.get("items").get(0).get("fields"), enabled));
    }

    @Test
    void budgetRevocationMasksDurableFinanceWhileTheSameBearerKeepsTokenEvidence() throws Exception {
        replace(Set.of(InstitutionWork.LLM_TOKEN_OPERATE, InstitutionWork.LLM_KEY_BUDGET));
        String token = bearer();
        Map<String, Object> body = body(Map.of("rpm", 71, "creditLimit", 2,
                "creditDeniedModels", Map.of("op", "ADD", "values", List.of("vendor/new")),
                "passthroughEndpoints", Map.of("op", "REMOVE", "values", List.of("images"))));
        JsonNode preview = postBody(token, BASE + "/preview", body);
        UUID operationId = UUID.randomUUID(); Map<String, Object> application = applyBody(body, preview, operationId);
        JsonNode applied = postBody(token, BASE, application);
        assertThat(applied.get("items").get(0).get("result").asString()).isEqualTo("APPLIED");
        Instant committedAt = jdbc.queryForObject("select updated_at from llm_api_keys where public_id=?", Instant.class, key);
        replace(Set.of(InstitutionWork.LLM_TOKEN_OPERATE));
        List<JsonNode> tokenViews = storedViews(token, preview, operationId, application);
        replace(Set.of(InstitutionWork.LLM_TOKEN_OPERATE, InstitutionWork.LLM_KEY_BUDGET));
        List<JsonNode> budgetViews = storedViews(token, preview, operationId, application);
        replace(Set.of(InstitutionWork.LLM_TOKEN_OPERATE, InstitutionWork.LLM_KEY_COST_READ));
        List<JsonNode> costViews = storedViews(token, preview, operationId, application);
        assertThat(jdbc.queryForObject("select updated_at from llm_api_keys where public_id=?", Instant.class, key)).isEqualTo(committedAt);
        assertThat(jdbc.queryForObject("select credit_limit from llm_api_keys where public_id=?", BigDecimal.class, key)).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("select rpm from llm_api_keys where public_id=?", Integer.class, key)).isEqualTo(71);
        assertAll(
                () -> assertTokenViews(tokenViews),
                () -> assertFinancialViews(budgetViews),
                () -> assertFinancialViews(costViews));
    }

    @Test
    void budgetRevocationHidesAnOlderMoneyAwareFingerprintEvenForATokenOnlyChange() throws Exception {
        replace(Set.of(InstitutionWork.LLM_TOKEN_OPERATE, InstitutionWork.LLM_KEY_BUDGET));
        String token = bearer(); Map<String, Object> body = body(Map.of("rpm", 71));
        JsonNode original = postBody(token, BASE + "/preview", body);
        String previousFingerprint = original.get("items").get(0).get("fingerprint").asString();
        assertThat(previousFingerprint).isNotBlank();
        replace(Set.of(InstitutionWork.LLM_TOKEN_OPERATE));
        JsonNode saved = getBody(token, BASE + "/previews/" + original.get("previewId").asString());
        JsonNode fresh = postBody(token, BASE + "/preview", body);
        assertThat(fresh.get("items").get(0).get("applicable").asBoolean()).isTrue();
        assertThat(fresh.get("items").get(0).get("fingerprint").asString()).isNotBlank().isNotEqualTo(previousFingerprint);
        assertAll(
                () -> assertThat(saved.get("items").get(0).get("fingerprint").asString()).isEmpty(),
                () -> assertThat(saved.get("items").get(0).get("applicable").asBoolean()).isFalse());
    }

    @Test
    void tokenOnlyCommittedPreviewKeepsItsOriginalFingerprintAndTokenDiff() throws Exception {
        String token = bearer(); Map<String, Object> body = body(Map.of("rpm", 71));
        JsonNode preview = postBody(token, BASE + "/preview", body);
        String fingerprint = preview.get("items").get(0).get("fingerprint").asString();
        JsonNode applied = postBody(token, BASE, applyBody(body, preview, UUID.randomUUID()));
        JsonNode saved = getBody(token, BASE + "/previews/" + preview.get("previewId").asString());
        assertThat(applied.get("items").get(0).get("result").asString()).isEqualTo("APPLIED");
        assertThat(saved.get("items").get(0).get("fingerprint").asString()).isEqualTo(fingerprint);
        assertThat(saved.get("items").get(0).get("applicable").asBoolean()).isTrue();
        assertTokenFields(saved.get("items").get(0).get("fields"));
        assertThat(saved.get("consumedOperationId").asString()).isEqualTo(applied.get("batchId").asString());
        assertThat(saved.get("change").get("llmKeyLimits").get("rpm").asInt()).isEqualTo(71);
        assertThat(preview.get("_fingerprintVisibility")).isNull();
        assertThat(applied.get("_fingerprintVisibility")).isNull();
        assertThat(saved.get("_fingerprintVisibility")).isNull();
    }

    @Test
    void legacyPreviewWithoutVisibilityMarkerKeepsFinanceReadersButHidesUnknownHashesFromTokenOnlyReaders() throws Exception {
        replace(Set.of(InstitutionWork.LLM_TOKEN_OPERATE, InstitutionWork.LLM_KEY_BUDGET));
        String token = bearer(); Map<String, Object> body = body(Map.of("rpm", 71));
        JsonNode preview = postBody(token, BASE + "/preview", body);
        String fingerprint = preview.get("items").get(0).get("fingerprint").asString();
        UUID previewId = UUID.fromString(preview.get("previewId").asString());
        jdbc.update("update admin_bulk_change_previews set preview=preview-'_fingerprintVisibility' where id=?", previewId);
        String legacyStored = jdbc.queryForObject("select preview::text from admin_bulk_change_previews where id=?", String.class, previewId);
        JsonNode budgetView = getBody(token, BASE + "/previews/" + previewId);
        replace(Set.of(InstitutionWork.LLM_TOKEN_OPERATE, InstitutionWork.LLM_KEY_COST_READ));
        JsonNode costView = getBody(token, BASE + "/previews/" + previewId);
        replace(Set.of(InstitutionWork.LLM_TOKEN_OPERATE));
        JsonNode tokenView = getBody(token, BASE + "/previews/" + previewId);
        assertThat(budgetView.get("items").get(0).get("fingerprint").asString()).isEqualTo(fingerprint);
        assertThat(budgetView.get("items").get(0).get("applicable").asBoolean()).isTrue();
        assertThat(costView.get("items").get(0).get("fingerprint").asString()).isEqualTo(fingerprint);
        assertThat(costView.get("items").get(0).get("applicable").asBoolean()).isTrue();
        assertThat(tokenView.get("items").get(0).get("fingerprint").asString()).isEmpty();
        assertThat(tokenView.get("items").get(0).get("applicable").asBoolean()).isFalse();
        assertTokenFields(tokenView.get("items").get(0).get("fields"));
        assertThat(tokenView.get("change").get("llmKeyLimits").get("rpm").asInt()).isEqualTo(71);
        assertThat(jdbc.queryForObject("select preview::text from admin_bulk_change_previews where id=?", String.class, previewId)).isEqualTo(legacyStored);
        assertThat(budgetView.get("_fingerprintVisibility")).isNull();
        assertThat(costView.get("_fingerprintVisibility")).isNull();
        assertThat(tokenView.get("_fingerprintVisibility")).isNull();
    }

    private List<JsonNode> storedViews(String token, JsonNode preview, UUID operation, Map<String, Object> application) throws Exception {
        JsonNode saved = getBody(token, BASE + "/previews/" + preview.get("previewId").asString());
        JsonNode result = getBody(token, BASE + "/operations/" + operation);
        JsonNode recent = getBody(token, BASE + "/operations");
        assertThat(recent.size()).isEqualTo(1);
        JsonNode duplicate = postBody(token, BASE, application);
        return List.of(saved, result, recent.get(0), duplicate);
    }

    private void assertTokenViews(List<JsonNode> views) {
        assertAll(
                () -> assertTokenFields(views.get(0).get("items").get(0).get("fields")),
                () -> assertHiddenChange(views.get(0)),
                () -> assertTokenFields(views.get(1).get("result").get("items").get(0).get("fields")),
                () -> assertHiddenChange(views.get(1)),
                () -> assertTokenFields(views.get(2).get("result").get("items").get(0).get("fields")),
                () -> assertHiddenChange(views.get(2)),
                () -> assertTokenFields(views.get(3).get("items").get(0).get("fields")),
                () -> assertTokenObservation(views.get(1)),
                () -> assertTokenObservation(views.get(2)));
    }

    private void assertFinancialViews(List<JsonNode> views) {
        assertAll(
                () -> assertFinancialFields(views.get(0).get("items").get(0).get("fields")),
                () -> assertThat(views.get(0).get("change").get("llmKeyLimits").get("creditLimit").asInt()).isEqualTo(2),
                () -> assertFinancialFields(views.get(1).get("result").get("items").get(0).get("fields")),
                () -> assertThat(views.get(1).get("change").get("llmKeyLimits").get("creditLimit").asInt()).isEqualTo(2),
                () -> assertFinancialFields(views.get(2).get("result").get("items").get(0).get("fields")),
                () -> assertFinancialFields(views.get(3).get("items").get(0).get("fields")),
                () -> assertThat(views.get(1).get("observations").get(0).get("values").get("creditLimit").asInt()).isEqualTo(2));
    }

    private void assertChatFields(JsonNode fields, boolean enabled) {
        assertThat(fieldNames(fields)).containsExactly("chatEnabled");
        assertThat(fields.get(0).get("oldValue").asBoolean()).isEqualTo(enabled);
        assertThat(fields.get(0).get("newValue").asBoolean()).isEqualTo(!enabled);
    }
    private void assertTokenFields(JsonNode fields) {
        assertThat(fieldNames(fields)).containsExactly("rpm");
        assertThat(fields.get(0).get("oldValue").asInt()).isEqualTo(60);
        assertThat(fields.get(0).get("newValue").asInt()).isEqualTo(71);
    }
    private void assertHiddenChange(JsonNode view) {
        assertThat(view.get("change") == null || view.get("change").isNull()).isTrue();
    }
    private void assertTokenObservation(JsonNode view) {
        JsonNode values = view.get("observations").get(0).get("values");
        assertThat(values.get("rpm").asInt()).isEqualTo(71);
        for (String name : List.of("creditLimit", "creditLimitReset", "creditDeniedModels", "passthroughEndpoints")) {
            assertThat(values.get(name)).isNull();
        }
    }
    private void assertFinancialFields(JsonNode fields) {
        assertThat(fieldNames(fields)).containsExactlyInAnyOrder("rpm", "creditLimit", "creditDeniedModels", "passthroughEndpoints");
        JsonNode amount = findField(fields, "creditLimit");
        assertThat(amount.get("oldValue").asInt()).isEqualTo(1); assertThat(amount.get("newValue").asInt()).isEqualTo(2);
    }
    private JsonNode findField(JsonNode fields, String name) {
        for (JsonNode field : fields) { if (field.get("field").asString().equals(name)) { return field; } }
        throw new AssertionError("Expected field " + name);
    }
    private List<String> fieldNames(JsonNode fields) {
        List<String> names = new ArrayList<>(); for (JsonNode field : fields) { names.add(field.get("field").asString()); } return names;
    }
    private List<String> strings(JsonNode values) {
        List<String> names = new ArrayList<>(); for (JsonNode value : values) { names.add(value.asString()); } return names;
    }
    private Map<String, Object> body(Map<String, Object> limits) {
        return Map.of("targetType", "LLM_KEY", "targetIds", List.of(key), "change", Map.of("kind", "LLM_KEY_LIMITS", "llmKeyLimits", limits));
    }
    private Map<String, Object> applyBody(Map<String, Object> body, JsonNode preview, UUID operation) {
        Map<String, Object> result = new HashMap<>(body); result.put("previewId", preview.get("previewId").asString()); result.put("operationId", operation);
        result.put("fingerprints", Map.of(key.toString(), preview.get("items").get(0).get("fingerprint").asString())); return result;
    }
    private JsonNode postBody(String token, String path, Map<String, Object> body) throws Exception {
        return json.readTree(mvc.perform(post(path).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode getBody(String token, String path) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private String bearer() { return "Bearer " + jwt.createAccessToken(actor); }
    private InstitutionAssignment assignment(Set<InstitutionWork> work) {
        return InstitutionAssignment.selected(actor.getPublicId(), institution.getPublicId(), work,
                Map.of(InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.listed(Set.of(workspace.getPublicId()))), 0);
    }
    private void replace(Set<InstitutionWork> work) {
        var encoded = new InstitutionAssignmentCodec().encode(assignment(work));
        jdbc.update("update user_org_roles set permission_mode=?,permission_payload=?::jsonb,permission_revision=nextval('institution_permission_revision_seq') where user_id=? and org_id=?",
                encoded.mode(), encoded.payload(), actor.getId(), institution.getId());
    }
}
