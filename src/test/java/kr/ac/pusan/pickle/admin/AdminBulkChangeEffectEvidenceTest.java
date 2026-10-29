package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.user.UserOrgRoleService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.jobrunr.jobs.lambdas.JobLambda;
import org.jobrunr.scheduling.JobScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Evidence of queue acceptance never substitutes for external completion. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminBulkChangeEffectEvidenceTest {
    private static final String BULK = "/api/v1/admin/bulk-changes";
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @Autowired private UserOrgRoleService orgRoles;
    @Autowired private OrgRepository orgs;
    @Autowired private PlatformTransactionManager transactions;
    @MockitoSpyBean private JobScheduler scheduler;
    private long org;
    private long workspace;
    private User actor;
    private String token;

    @BeforeEach
    void fixtures() {
        org = SeedFixtures.seedOrgId(jdbc);
        actor = new User("queue-receipt-" + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", "합성 접수 담당자");
        actor.setRole(UserRole.SYS_ADMIN);
        actor.setStatus(UserStatus.ACTIVE);
        actor.setEmailVerifiedAt(Instant.now());
        actor = users.save(actor);
        token = jwt.createAccessToken(actor);
        workspace = jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT',?) returning id",
                Long.class, "합성 접수 " + UUID.randomUUID());
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, actor.getId());
    }

    @AfterEach
    void restoreScheduler() { reset(scheduler); }

    @Test
    void actualQueueReferenceAndCurrentJobStateDoNotClaimDomainVerificationCompletion() throws Exception {
        long domain = customDomain();
        UUID target = SeedFixtures.publicId(jdbc, "domains", domain);
        Map<String, Object> body = prepare(target);
        long before = jobs();
        JsonNode result = apply(body);
        JsonNode receipt = receipt(body);
        JsonNode queue = queue(receipt);
        assertThat(queue.get("outcome").asString()).isEqualTo("ACCEPTED");
        UUID reference = UUID.fromString(queue.get("externalReference").asString());
        assertThat(jdbc.queryForObject("select count(*) from jobrunr_jobs where id=?", Long.class, reference.toString())).isEqualTo(1);
        assertThat(queue.get("queueState").asString()).isEqualTo("ENQUEUED");
        assertThat(queue.get("queueObservedAt").isNull()).isFalse();
        assertThat(receipt.get("status").asString()).isEqualTo("COMMITTED");
        assertThat(receipt.get("result")).isEqualTo(result);
        assertThat(receipt.get("observations").get(0).get("values").propertyNames()).containsExactlyInAnyOrder("kind", "status");
        assertThat(receipt.get("observations").get(0).get("values").get("status").asString()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select status::text from domains where id=?", String.class, domain)).isEqualTo("PENDING");
        for (JsonNode effect : receipt.get("effects")) {
            if (effect.get("kind").asString().equals("AUDIT")) {
                assertThat(effect.get("outcome").asString()).isEqualTo("COMPLETED");
            }
        }
        assertThat(apply(body)).isEqualTo(result);
        assertThat(jobs()).isEqualTo(before + 1);
        assertThat(audits()).isEqualTo(1);
    }

    @Test
    void anInFlightVerifierNormalReturnIsUnknownWithoutAnAcceptedJobReference() throws Exception {
        long domain = customDomain();
        UUID target = SeedFixtures.publicId(jdbc, "domains", domain);
        long before = jobs();
        apply(prepare(target));
        Map<String, Object> anotherOperation = prepare(target);
        JsonNode result = apply(anotherOperation);
        JsonNode effect = queue(receipt(anotherOperation));
        assertThat(effect.get("outcome").asString()).isEqualTo("UNKNOWN");
        assertThat(effect.get("externalReference").isNull()).isTrue();
        assertThat(effect.get("queueState").isNull()).isTrue();
        assertThat(apply(anotherOperation)).isEqualTo(result);
        assertThat(jobs()).isEqualTo(before + 1);
        assertThat(audits()).isEqualTo(2);
    }

    @Test
    void anEnqueueFailureRetainsCommittedResultAndOtherAuditsWithoutReplay() throws Exception {
        UUID target = SeedFixtures.publicId(jdbc, "domains", customDomain());
        Map<String, Object> body = prepare(target);
        long before = jobs();
        doThrow(new IllegalStateException("synthetic queue unavailable")).when(scheduler).enqueue(any(JobLambda.class));
        JsonNode result = apply(body);
        assertThat(result.get("items").get(0).get("result").asString()).isEqualTo("APPLIED");
        JsonNode effect = queue(receipt(body));
        assertThat(effect.get("outcome").asString()).isEqualTo("FAILED");
        assertThat(effect.get("externalReference").isNull()).isTrue();
        reset(scheduler);
        assertThat(apply(body)).isEqualTo(result);
        assertThat(jobs()).isEqualTo(before);
        assertThat(audits()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where actor_id=? and action='domain.admin_verify'",
                Long.class, actor.getId())).isEqualTo(1);
    }

    @Test
    void failedLateEvidenceStorageLeavesRegistrationAndNeverRepeatsTheCommittedJob() throws Exception {
        UUID target = SeedFixtures.publicId(jdbc, "domains", customDomain());
        Map<String, Object> body = prepare(target);
        long before = jobs();
        jdbc.execute("""
                create function receipt_observation_failure() returns trigger language plpgsql as $$
                begin raise exception 'synthetic receipt observation failure'; end $$
                """);
        jdbc.execute("create trigger receipt_observation_failure before update on admin_bulk_change_effects for each row execute function receipt_observation_failure()");
        JsonNode result;
        try {
            result = apply(body);
            assertThat(result.get("items").get(0).get("result").asString()).isEqualTo("APPLIED");
        } finally {
            jdbc.execute("drop trigger receipt_observation_failure on admin_bulk_change_effects");
            jdbc.execute("drop function receipt_observation_failure()");
        }
        JsonNode saved = receipt(body);
        assertThat(saved.get("result")).isEqualTo(result);
        for (JsonNode effect : saved.get("effects")) {
            assertThat(effect.get("outcome").asString()).isEqualTo("REGISTERED");
            assertThat(effect.get("externalReference").isNull()).isTrue();
        }
        assertThat(apply(body)).isEqualTo(result);
        assertThat(jobs()).isEqualTo(before + 1);
        assertThat(audits()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where actor_id=? and action='domain.admin_verify'",
                Long.class, actor.getId())).isEqualTo(1);
    }

    @Test
    void anAcceptedQueueReferenceIsMaskedWhenCurrentInstitutionScopeIsLost() throws Exception {
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                SeedFixtures.replaceOrgRole(jdbc, actor.getId(), org, UserRole.ORG_ADMIN));
        UUID target = SeedFixtures.publicId(jdbc, "domains", customDomain());
        Map<String, Object> body = prepare(target);
        long before = jobs();
        JsonNode original = apply(body);
        JsonNode accepted = queue(receipt(body));
        String reference = accepted.get("externalReference").asString();
        assertThat(reference).isNotBlank();
        assertThat(accepted.get("queueState").asString()).isEqualTo("ENQUEUED");
        long other = orgs.save(new Org("합성 접수 범위 변경 " + UUID.randomUUID(), null)).getId();
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                SeedFixtures.replaceOrgRole(jdbc, actor.getId(), other, UserRole.ORG_MANAGER));
        JsonNode masked = receipt(body);
        assertThat(masked.get("change").isNull()).isTrue();
        assertThat(masked.get("result").get("items").get(0).get("result").asString()).isEqualTo("APPLIED");
        assertThat(masked.get("result").get("items").get(0).get("name").isNull()).isTrue();
        assertThat(queue(masked).get("externalReference").isNull()).isTrue();
        assertThat(queue(masked).get("queueState").isNull()).isTrue();
        assertThat(queue(masked).get("queueObservedAt").isNull()).isTrue();
        assertThat(masked.toString()).doesNotContain(reference);
        assertThat(apply(body)).isEqualTo(masked.get("result"));
        assertThat(jdbc.queryForObject("select external_reference from admin_bulk_change_effects where id=?",
                String.class, UUID.fromString(accepted.get("id").asString()))).isEqualTo(reference);
        assertThat(mapper.readTree(jdbc.queryForObject("select result::text from admin_bulk_change_operations where id=?",
                String.class, UUID.fromString(body.get("operationId").toString())))).isEqualTo(original);
        assertThat(jobs()).isEqualTo(before + 1);
        assertThat(audits()).isEqualTo(1);
    }

    private Map<String, Object> prepare(UUID id) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("targetType", "DOMAIN");
        body.put("targetIds", List.of(id.toString()));
        body.put("change", Map.of("kind", "DOMAIN_VERIFY", "domainVerify", Map.of()));
        JsonNode preview = mapper.readTree(mvc.perform(post(BULK + "/preview").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(preview.get("previewId").isNull()).isFalse();
        body.put("previewId", preview.get("previewId").asString());
        body.put("operationId", UUID.randomUUID().toString());
        body.put("fingerprints", Map.of(id.toString(), preview.get("items").get(0).get("fingerprint").asString()));
        return body;
    }

    private JsonNode apply(Map<String, Object> body) throws Exception {
        JsonNode result = mapper.readTree(mvc.perform(post(BULK).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(result.get("batchId").asString()).isEqualTo(body.get("operationId"));
        return result;
    }

    private JsonNode receipt(Map<String, Object> body) throws Exception {
        return mapper.readTree(mvc.perform(get(BULK + "/operations/" + body.get("operationId")).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode queue(JsonNode receipt) {
        for (JsonNode effect : receipt.get("effects")) {
            if (effect.get("kind").asString().equals("QUEUE")) { return effect; }
        }
        throw new AssertionError("verification queue evidence missing");
    }

    private long customDomain() {
        long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        long node = jdbc.queryForObject("select id from nodes where name='pve1'", Long.class);
        long request = RequestFixtures.insertVmRequest(jdbc, workspace, org, actor.getId(), "합성 접수 검증", image);
        RequestFixtures.approveVmRequest(jdbc, request, actor.getId(), image, 2, 2048, 10);
        String name = "queue-" + UUID.randomUUID().toString().substring(0, 12);
        long vm = jdbc.queryForObject("""
                insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status,end_date)
                values(?,?,?,?,?,?,?,2,2048,10,'RUNNING',?) returning id
                """, Long.class, node, workspace, org, request, name, name, image, LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(30));
        return jdbc.queryForObject("""
                insert into domains(vm_id,workspace_id,org_id,kind,fqdn,verification_token,status)
                values(?,?,?,'CUSTOM',?,'synthetic-verification','PENDING') returning id
                """, Long.class, vm, workspace, org, name + ".example.test");
    }
    private long jobs() { return jdbc.queryForObject("select count(*) from jobrunr_jobs where jobsignature like '%DomainVerificationJob.verify(%'", Long.class); }
    private long audits() { return jdbc.queryForObject("select count(*) from audit_logs where actor_id=? and action='admin.bulk_change'", Long.class, actor.getId()); }
}
