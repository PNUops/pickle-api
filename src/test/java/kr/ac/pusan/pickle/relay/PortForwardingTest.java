package kr.ac.pusan.pickle.relay;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.AccessGrantFixtures;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
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
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Self-service port forwarding + admin intervention: access-list authz,
 * kill switch, random in-band allocation (cross-proto exclusive, retries,
 * honest exhaustion, race-free under concurrency), generation bumps on every
 * write, derived apply states, sudo-gated token issue, suspend/unsuspend,
 * tri-state guard PATCH, and the no-orphan-mapping teardown invariant.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class PortForwardingTest {

    private static final AtomicInteger SOURCE_SEQ = new AtomicInteger(1);
    private static final AtomicInteger IP_SEQ = new AtomicInteger(1);
    // Every suite in the shared embedded PG needs its OWN proxmox_vmid base
    // (vms_proxmox_vmid_active_uq is global): pick an unused range by grepping
    // VMID_SEQ across src/test before adding one.
    private static final AtomicInteger VMID_SEQ = new AtomicInteger(903_000);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PortMappingTeardownService portMappingTeardown;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private User owner;
    private User editor;
    private User viewer;
    private String ownerToken;
    private String editorToken;
    private String viewerToken;
    private String outsiderToken;
    private String sysAdminToken;
    private long workspaceId;

    @BeforeEach
    void setUp() throws Exception {
        jdbcTemplate.update(
                "update settings set value = 'true' where key = 'port_forwarding_enabled'");
        // One shared fixture owner allocates across every test of this class
        // inside one rate-limit hour window — lift the per-user budget so only
        // the dedicated rate-limit test (which pins its own value and its own
        // fresh user) exercises the 429 path.
        jdbcTemplate.update("""
                update settings set value = '500'
                 where key = 'port_forward_alloc_limit_per_hour'
                """);
        owner = ensureUser("pf.owner@pusan.ac.kr", "포워딩소유자");
        editor = ensureUser("pf.editor@pusan.ac.kr", "포워딩편집자");
        viewer = ensureUser("pf.viewer@pusan.ac.kr", "포워딩뷰어");
        User outsider = ensureUser("pf.outsider@pusan.ac.kr", "포워딩외부인");
        User sysAdmin = userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow();
        ownerToken = jwtService.createAccessToken(owner);
        editorToken = jwtService.createAccessToken(editor);
        viewerToken = jwtService.createAccessToken(viewer);
        outsiderToken = jwtService.createAccessToken(outsider);
        sysAdminToken = jwtService.createAccessToken(sysAdmin);
        workspaceId = createTeam("pf-" + UUID.randomUUID().toString().substring(0, 8));
        // Membership only puts them in the workspace; what each may do to a VM is
        // written onto that VM's access list by runningVm().
        addMember(workspaceId, editor.getEmail(), "MEMBER");
        addMember(workspaceId, viewer.getEmail(), "MEMBER");
    }

    // ── authorization ───────────────────────────────────────────────────────

    @Test
    void createAuthorizesByTheVmAccessList() throws Exception {
        long relayId = soleRelay(10000, 10099);
        long vmId = runningVm();
        // outside the owning workspace the VM does not exist; granted VIEWER it
        // does, but a forwarding is a configuration change
        create(vmId, outsiderToken, "TCP", 8080)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        create(vmId, viewerToken, "TCP", 8080)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("WORKSPACE_ROLE_INSUFFICIENT"));
        create(vmId, editorToken, "TCP", 8080)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proto").value("TCP"))
                .andExpect(jsonPath("$.targetPort").value(8080))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.applyState").value("PENDING"));
        assertThat(mappingGeneration(relayId)).isEqualTo(1);
    }

    @Test
    void killSwitchRefusesCreation() throws Exception {
        soleRelay(10000, 10099);
        long vmId = runningVm();
        jdbcTemplate.update(
                "update settings set value = 'false' where key = 'port_forwarding_enabled'");
        try {
            create(vmId, ownerToken, "TCP", 8080)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("PORT_FORWARDING_DISABLED"));
        } finally {
            jdbcTemplate.update(
                    "update settings set value = 'true' where key = 'port_forwarding_enabled'");
        }
    }

    @Test
    void nonRunningVmIsRefused() throws Exception {
        soleRelay(10000, 10099);
        long vmId = runningVm();
        jdbcTemplate.update("update vms set status = 'STOPPED'::vm_status where id = ?", vmId);
        create(vmId, ownerToken, "TCP", 8080)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
    }

    @Test
    void guestSshPortIsRefusedAsTarget() throws Exception {
        long relayId = soleRelay(10200, 10299);
        long vmId = runningVm();
        // A mapping to the guest's own sshd would answer password prompts from
        // the internet with the SSH gateway, and every policy it enforces, out
        // of the path — refused for both protocols before anything is written.
        create(vmId, ownerToken, "TCP", 22)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("targetPort"));
        create(vmId, ownerToken, "UDP", 22)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        Long mappings = jdbcTemplate.queryForObject(
                "select count(*) from port_mappings where vm_id = ?", Long.class, vmId);
        assertThat(mappings).isZero();
        assertThat(mappingGeneration(relayId)).isZero();
        // neighbouring ports stay allocatable
        create(vmId, ownerToken, "TCP", 23).andExpect(status().isCreated());
    }

    // ── allocation ──────────────────────────────────────────────────────────

    @Test
    void allocationStaysInsideTheBandAndBumpsPerWrite() throws Exception {
        long relayId = soleRelay(11000, 11004);
        long vmId = runningVm();
        Set<Integer> ports = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            String body = create(vmId, ownerToken, "TCP", 8080 + i)
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            int port = objectMapper.readTree(body).get("publicPort").asInt();
            assertThat(port).isBetween(11000, 11004);
            ports.add(port);
        }
        assertThat(ports).hasSize(5); // collision retry never reuses a port
        assertThat(mappingGeneration(relayId)).isEqualTo(5); // every write bumps
    }

    @Test
    void portNumbersAreCrossProtoExclusiveAndExhaustHonestly() throws Exception {
        soleRelay(12000, 12001); // band of exactly two ports
        long vmId = runningVm();
        int tcpPort = createdPort(create(vmId, ownerToken, "TCP", 80));
        int udpPort = createdPort(create(vmId, ownerToken, "UDP", 53));
        // udp did NOT reuse tcp's number even though the unique key is
        // per-proto — allocation treats a port as taken for either proto.
        assertThat(udpPort).isNotEqualTo(tcpPort);
        create(vmId, ownerToken, "UDP", 54)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLIC_PORT_EXHAUSTED"));
    }

    @Test
    void concurrentCreatesNeverAllocateTheSamePort() throws Exception {
        long relayId = soleRelay(13000, 13099);
        long vmId = runningVm();
        int workers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                int targetPort = 9000 + i;
                calls.add(() -> createdPort(create(vmId, ownerToken, "TCP", targetPort)));
            }
            Set<Integer> ports = new HashSet<>();
            for (Future<Integer> future : pool.invokeAll(calls)) {
                ports.add(future.get());
            }
            assertThat(ports).hasSize(workers);
        } finally {
            pool.shutdownNow();
        }
        assertThat(mappingGeneration(relayId)).isEqualTo(workers);
        Long distinct = jdbcTemplate.queryForObject(
                "select count(distinct public_port) from port_mappings where relay_id = ?",
                Long.class, relayId);
        assertThat(distinct).isEqualTo(workers);
    }

    @Test
    void allocationIsHourlyRateLimitedPerUser() throws Exception {
        soleRelay(14000, 14099);
        long vmId = runningVm();
        jdbcTemplate.update("""
                update settings set value = '2' where key = 'port_forward_alloc_limit_per_hour'
                """);
        try {
            User fresh = ensureUser("pf.limited@pusan.ac.kr", "포워딩제한");
            addMember(workspaceId, fresh.getEmail(), "MEMBER");
            // The VM predates this account, so its access list has to be told
            // about them before they can allocate anything.
            AccessGrantFixtures.grantVmToUser(jdbcTemplate, vmId, fresh.getId(), "EDITOR");
            String freshToken = jwtService.createAccessToken(fresh);
            create(vmId, freshToken, "TCP", 8081).andExpect(status().isCreated());
            create(vmId, freshToken, "TCP", 8082).andExpect(status().isCreated());
            create(vmId, freshToken, "TCP", 8083)
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        } finally {
            // back to the lifted fixture budget (setUp), not the seed default
            jdbcTemplate.update("""
                    update settings set value = '500'
                     where key = 'port_forward_alloc_limit_per_hour'
                    """);
        }
    }

    // ── apply state + delete ────────────────────────────────────────────────

    @Test
    void applyStateFollowsRelayConfirmationAndErrors() throws Exception {
        long relayId = soleRelay(15000, 15099);
        long vmId = runningVm();
        String body = create(vmId, ownerToken, "TCP", 8080)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.applyState").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long mappingId = SeedFixtures.internalId(jdbcTemplate, "port_mappings", UUID.fromString(objectMapper.readTree(body).get("id").asString()));

        // agent confirms the generation -> ACTIVE
        jdbcTemplate.update(
                "update relays set applied_generation = mapping_generation where id = ?", relayId);
        list(vmId, ownerToken)
                .andExpect(jsonPath("$[0].applyState").value("ACTIVE"));

        // relay reports an apply error naming this mapping -> FAILED overrides
        jdbcTemplate.update("update relays set last_error = ? where id = ?",
                "[{\"mappingId\":" + mappingId + ",\"message\":\"apply failed\"}]", relayId);
        list(vmId, ownerToken)
                .andExpect(jsonPath("$[0].applyState").value("FAILED"));
    }

    @Test
    void deleteRemovesTheMappingAndBumps() throws Exception {
        long relayId = soleRelay(16000, 16099);
        long vmId = runningVm();
        String body = create(vmId, ownerToken, "UDP", 5000)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long mappingId = SeedFixtures.internalId(jdbcTemplate, "port_mappings", UUID.fromString(objectMapper.readTree(body).get("id").asString()));
        mockMvc.perform(delete("/api/v1/vms/" + pub("vms", vmId) + "/port-forwardings/" + pub("port_mappings", mappingId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from port_mappings where id = ?", Long.class, mappingId);
        assertThat(rows).isZero();
        assertThat(mappingGeneration(relayId)).isEqualTo(2);
    }

    // ── token issue (sudo-gated) ────────────────────────────────────────────

    @Test
    void tokenIssueIsReauthGatedAndStoresOnlyTheHash() throws Exception {
        String sourceIp = "198.51.101." + SOURCE_SEQ.getAndIncrement();
        long relayId = jdbcTemplate.queryForObject("""
                insert into relays (name, source_ip, port_band_start, port_band_end)
                values (?, ?, 17000, 17099) returning id
                """, Long.class, "token-" + UUID.randomUUID().toString().substring(0, 8),
                sourceIp);

        mockMvc.perform(post("/api/v1/admin/relays/" + pub("relays", relayId) + "/token")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));

        String body = mockMvc.perform(post("/api/v1/admin/relays/" + pub("relays", relayId) + "/token")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .header(ReauthTestSupport.HEADER, reauth(sysAdminToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asString();
        assertThat(token).hasSize(64).matches("[0-9a-f]{64}"); // hex only, no '='

        String storedHash = jdbcTemplate.queryForObject(
                "select token_hash from relays where id = ?", String.class, relayId);
        assertThat(storedHash.strip()).isEqualTo(ReauthTestSupport.sha256Hex(token));

        // end-to-end: the freshly issued token authenticates a sync
        mockMvc.perform(post("/internal/relays/" + relayId + "/sync")
                        .with(request -> {
                            request.setRemoteAddr(sourceIp);
                            return request;
                        })
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appliedGeneration\":0}"))
                .andExpect(status().isOk());

        Long audits = jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action = 'relay.token_issue' and target_id = ?
                """, Long.class, pub("relays", relayId).toString());
        assertThat(audits).isEqualTo(1);
    }

    // ── admin intervention ──────────────────────────────────────────────────

    @Test
    void adminSuspendUnsuspendCycleBumpsAndNotifies() throws Exception {
        long relayId = soleRelay(18000, 18099);
        long vmId = runningVm();
        String body = create(vmId, ownerToken, "TCP", 8080)
                .andReturn().getResponse().getContentAsString();
        long mappingId = SeedFixtures.internalId(jdbcTemplate, "port_mappings", UUID.fromString(objectMapper.readTree(body).get("id").asString()));

        mockMvc.perform(post("/api/v1/admin/port-mappings/" + pub("port_mappings", mappingId) + "/suspend")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"약관 위반 신고 확인 중\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.suspendedReason").value("약관 위반 신고 확인 중"));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select status, suspended_by from port_mappings where id = ?", mappingId);
        assertThat(row.get("status")).isEqualTo("SUSPENDED");
        assertThat(((Number) row.get("suspended_by")).longValue())
                .isEqualTo(SeedFixtures.sysadminId(jdbcTemplate));
        assertThat(mappingGeneration(relayId)).isEqualTo(2);
        Long ownerNotified = jdbcTemplate.queryForObject("""
                select count(*) from notifications
                 where user_id = ? and event = 'port_mapping.suspended'
                """, Long.class, owner.getId());
        assertThat(ownerNotified).isEqualTo(1);

        // suspending again is a state conflict
        mockMvc.perform(post("/api/v1/admin/port-mappings/" + pub("port_mappings", mappingId) + "/suspend")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"중복\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/admin/port-mappings/" + pub("port_mappings", mappingId) + "/unsuspend")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbcTemplate.queryForObject(
                "select status from port_mappings where id = ?", String.class, mappingId))
                .isEqualTo("ACTIVE");
        assertThat(mappingGeneration(relayId)).isEqualTo(3);
    }

    @Test
    void adminDeleteRemovesBumpsAndNotifiesTheWorkspace() throws Exception {
        long relayId = soleRelay(21000, 21099);
        long vmId = runningVm();
        String body = create(vmId, ownerToken, "TCP", 8080)
                .andReturn().getResponse().getContentAsString();
        long mappingId = SeedFixtures.internalId(jdbcTemplate, "port_mappings", UUID.fromString(objectMapper.readTree(body).get("id").asString()));

        mockMvc.perform(delete("/api/v1/admin/port-mappings/" + pub("port_mappings", mappingId))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isAccepted());

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from port_mappings where id = ?", Long.class, mappingId))
                .isZero();
        assertThat(mappingGeneration(relayId)).isEqualTo(2);
        // The owning workspace is told, same channel as an admin suspend: an
        // access path disappearing must not be discovered from a dead
        // connection (the audit row alone reaches no user).
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from notifications
                 where user_id = ? and event = 'port_mapping.deleted'
                   and payload ->> 'vmId' = ?
                """, Long.class, owner.getId(), pub("vms", vmId).toString())).isEqualTo(1);
    }

    @Test
    void guardPatchIsTriStatePerField() throws Exception {
        long relayId = soleRelay(19000, 19099);
        long vmId = runningVm();
        String body = create(vmId, ownerToken, "TCP", 8080)
                .andReturn().getResponse().getContentAsString();
        long mappingId = SeedFixtures.internalId(jdbcTemplate, "port_mappings", UUID.fromString(objectMapper.readTree(body).get("id").asString()));

        // set two guards (0 = disable is a legal stored value)
        mockMvc.perform(patch("/api/v1/admin/port-mappings/" + pub("port_mappings", mappingId) + "/guards")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ctMax\":256,\"newConnRate\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ctMax").value(256))
                .andExpect(jsonPath("$.newConnRate").value(0));
        assertThat(mappingGeneration(relayId)).isEqualTo(2);

        // omitted field keeps its value; explicit null clears to default
        mockMvc.perform(patch("/api/v1/admin/port-mappings/" + pub("port_mappings", mappingId) + "/guards")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ctMax\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ctMax").doesNotExist())
                .andExpect(jsonPath("$.newConnRate").value(0));
        assertThat(mappingGeneration(relayId)).isEqualTo(3);

        // negative values and empty bodies are refused
        mockMvc.perform(patch("/api/v1/admin/port-mappings/" + pub("port_mappings", mappingId) + "/guards")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"perSourceRate\":-1}"))
                .andExpect(status().isUnprocessableEntity());
        mockMvc.perform(patch("/api/v1/admin/port-mappings/" + pub("port_mappings", mappingId) + "/guards")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void guardPatchRefusesValuesBeyondTheCeiling() throws Exception {
        long relayId = soleRelay(23000, 23099);
        long vmId = runningVm();
        String body = create(vmId, ownerToken, "TCP", 8080)
                .andReturn().getResponse().getContentAsString();
        long mappingId = SeedFixtures.internalId(jdbcTemplate, "port_mappings", UUID.fromString(objectMapper.readTree(body).get("id").asString()));
        long generationBefore = mappingGeneration(relayId);

        // A value the relay's packet filter cannot render fails the whole
        // table apply there, and a table-level failure names no mapping — so
        // an absurd guard would freeze the relay with nothing pointing at the
        // offending row. Refuse it at the edit instead.
        int beyond = AdminPortMappingService.GUARD_VALUE_MAX + 1;
        guardPatch(mappingId, "{\"ctMax\":" + beyond + "}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("ctMax"))
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("ctMax")));
        guardPatch(mappingId, "{\"newConnRate\":2147483647}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("newConnRate"));
        guardPatch(mappingId, "{\"perSourceRate\":10,\"perSourceBurst\":" + beyond + "}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("perSourceBurst"));
        assertThat(mappingGeneration(relayId)).isEqualTo(generationBefore);

        // the ceiling itself is still a legal value
        guardPatch(mappingId, "{\"ctMax\":" + AdminPortMappingService.GUARD_VALUE_MAX + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ctMax").value(AdminPortMappingService.GUARD_VALUE_MAX));
    }

    @Test
    void guardPatchRefusesABurstWithoutItsRate() throws Exception {
        long relayId = soleRelay(22000, 22099);
        long vmId = runningVm();
        String body = create(vmId, ownerToken, "TCP", 8080)
                .andReturn().getResponse().getContentAsString();
        long mappingId = SeedFixtures.internalId(jdbcTemplate, "port_mappings", UUID.fromString(objectMapper.readTree(body).get("id").asString()));
        long generationBefore = mappingGeneration(relayId);

        // A burst without a positive matching rate would make every agent
        // reject the WHOLE snapshot (strict applier) — the resulting state is
        // validated, so none of these may ever be stored.
        guardPatch(mappingId, "{\"newConnBurst\":400}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("newConnBurst"));
        guardPatch(mappingId, "{\"newConnRate\":0,\"newConnBurst\":400}")
                .andExpect(status().isUnprocessableEntity());
        guardPatch(mappingId, "{\"perSourceBurst\":50}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors[0].field").value("perSourceBurst"));
        // refused writes never bump the generation (no phantom change)
        assertThat(mappingGeneration(relayId)).isEqualTo(generationBefore);

        // the consistent pair lands
        guardPatch(mappingId, "{\"newConnRate\":200,\"newConnBurst\":400}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newConnRate").value(200))
                .andExpect(jsonPath("$.newConnBurst").value(400));
        // and cannot be broken afterwards: clearing or zeroing the rate while
        // the burst stays would orphan it
        guardPatch(mappingId, "{\"newConnRate\":null}")
                .andExpect(status().isUnprocessableEntity());
        guardPatch(mappingId, "{\"newConnRate\":0}")
                .andExpect(status().isUnprocessableEntity());
        // clearing both together is fine
        guardPatch(mappingId, "{\"newConnRate\":null,\"newConnBurst\":null}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newConnRate").doesNotExist())
                .andExpect(jsonPath("$.newConnBurst").doesNotExist());
    }

    private ResultActions guardPatch(long mappingId, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/admin/port-mappings/" + pub("port_mappings", mappingId) + "/guards")
                .header("Authorization", "Bearer " + sysAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    // ── teardown invariant ──────────────────────────────────────────────────

    @Test
    void errorVmDeletionRemovesMappingsWithTheIpRelease() throws Exception {
        long relayId = soleRelay(20000, 20099);
        long vmId = runningVm();
        create(vmId, ownerToken, "TCP", 8080).andExpect(status().isCreated());
        long generationBefore = mappingGeneration(relayId);
        jdbcTemplate.update("update vms set status = 'ERROR'::vm_status where id = ?", vmId);

        mockMvc.perform(delete("/api/v1/vms/" + pub("vms", vmId))
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                .andExpect(status().isAccepted());

        Long mappings = jdbcTemplate.queryForObject(
                "select count(*) from port_mappings where vm_id = ?", Long.class, vmId);
        assertThat(mappings).isZero(); // no orphan mapping survives the release
        assertThat(mappingGeneration(relayId)).isEqualTo(generationBefore + 1);
        String allocationStatus = jdbcTemplate.queryForObject(
                "select status from ip_allocations where vm_id = ?", String.class, vmId);
        assertThat(allocationStatus).isEqualTo("RELEASED");
    }

    @Test
    void teardownServiceDemandsACallerTransaction() throws Exception {
        long relayId = soleRelay(21000, 21099);
        long vmId = runningVm();
        create(vmId, ownerToken, "TCP", 8080).andExpect(status().isCreated());

        // MANDATORY propagation: mapping delete must never commit separately
        // from the caller's IP-release transaction.
        assertThatThrownBy(() -> portMappingTeardown.deleteMappingsForVm(vmId))
                .isInstanceOf(IllegalTransactionStateException.class);

        transactionTemplate.executeWithoutResult(tx ->
                portMappingTeardown.deleteMappingsForVm(vmId));
        Long rows = jdbcTemplate.queryForObject(
                "select count(*) from port_mappings where vm_id = ?", Long.class, vmId);
        assertThat(rows).isZero();
        assertThat(mappingGeneration(relayId)).isEqualTo(2);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private ResultActions create(long vmId, String token, String proto, int targetPort)
            throws Exception {
        return mockMvc.perform(post("/api/v1/vms/" + pub("vms", vmId) + "/port-forwardings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"proto\":\"" + proto + "\",\"targetPort\":" + targetPort + "}"));
    }

    private ResultActions list(long vmId, String token) throws Exception {
        return mockMvc.perform(get("/api/v1/vms/" + pub("vms", vmId) + "/port-forwardings")
                .header("Authorization", "Bearer " + token));
    }

    private int createdPort(ResultActions result) throws Exception {
        String body = result.andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("publicPort").asInt();
    }

    private long mappingGeneration(long relayId) {
        return jdbcTemplate.queryForObject(
                "select mapping_generation from relays where id = ?", Long.class, relayId);
    }

    /** Disables every other relay and creates the test's own enabled one. */
    private long soleRelay(int bandStart, int bandEnd) {
        jdbcTemplate.update("update relays set enabled = false");
        return jdbcTemplate.queryForObject("""
                insert into relays (name, source_ip, port_band_start, port_band_end, public_host)
                values (?, ?, ?, ?, 'relay.example.com') returning id
                """, Long.class, "pf-" + UUID.randomUUID().toString().substring(0, 8),
                "198.51.102." + SOURCE_SEQ.getAndIncrement(), bandStart, bandEnd);
    }

    private long runningVm() {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, owner.getId(), "포트 포워딩 테스트", null, 1, 1024, 10);
        String hostname = "pf-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values ((select min(id) from nodes), ?, ?, ?, ?, ?,
                        (select min(id) from os_images), 1, 1024, 10, ?, 'RUNNING'::vm_status)
                returning id
                """, Long.class, workspaceId, orgId, requestId, hostname, hostname,
                VMID_SEQ.incrementAndGet());
        String ip = "172.29.220." + IP_SEQ.getAndIncrement();
        long allocId = jdbcTemplate.queryForObject("""
                insert into ip_allocations (pool_id, ip, vm_id, status)
                values ((select id from ip_pools where name = 'guest-private'), ?::inet, ?,
                        'ALLOCATED')
                returning id
                """, Long.class, ip, vmId);
        jdbcTemplate.update("update vms set ip_allocation_id = ? where id = ?", allocId, vmId);
        // Inserted VMs carry no access list, so state it here: the requester as
        // the owner approval would have made them, and the two other fixture
        // accounts at the rungs the forwarding checks distinguish.
        AccessGrantFixtures.grantVmToUser(jdbcTemplate, vmId, owner.getId(), "OWNER");
        AccessGrantFixtures.grantVmToUser(jdbcTemplate, vmId, editor.getId(), "EDITOR");
        AccessGrantFixtures.grantVmToUser(jdbcTemplate, vmId, viewer.getId(), "VIEWER");
        return vmId;
    }

    private long createTeam(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", "포워딩 테스트 " + slug,
                                        "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return SeedFixtures.internalId(jdbcTemplate, "workspaces", UUID.fromString(objectMapper.readTree(body).get("id").asString()));
    }

    private void addMember(long workspaceId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/workspaces/" + pub("workspaces", workspaceId) + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "role", role))))
                .andExpect(status().isCreated());
    }

    private String reauth(String token) {
        return ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService, token);
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            user.setRole(UserRole.USER);
            return userRepository.save(user);
        });
    }

    /** The public identifier of a row this test set up through direct SQL. */
    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
