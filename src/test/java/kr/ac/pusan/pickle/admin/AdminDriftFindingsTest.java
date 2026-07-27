package kr.ac.pusan.pickle.admin;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.provisioning.DriftReconciler;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ProxmoxWireMockSupport;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
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

/**
 * Drift report: DriftReconciler persistence (upsert/bump/auto-resolve for
 * all three drift classes, including the previously log-only unmanaged-guest
 * class ②) and the SYS_ADMIN drift-findings API (list default-OPEN + kind
 * filter, CAS resolve with 409/404). Assertions are per-dedup-key — the
 * database is shared with the other admin tests.
 */
@SpringBootTest(properties = {
        "pickle.proxmox.token-id=pickle@pve!test",
        "pickle.proxmox.token-secret=wiremock-test-secret"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminDriftFindingsTest {

    private static ProxmoxWireMockSupport wm;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DriftReconciler reconciler;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private long templateId;
    private long requesterId;
    private long groupId;
    private String sysAdminToken;
    private String orgAdminToken;
    private final List<Long> createdNodeIds = new ArrayList<>();

    @BeforeAll
    static void startServer() {
        wm = ProxmoxWireMockSupport.start();
    }

    @AfterAll
    static void stopServer() {
        wm.close();
    }

    @BeforeEach
    void setUp() {
        wm.reset();
        // All pre-existing nodes out of scope: leftover nodes from other test
        // classes must not fail listings and block ② auto-resolve here.
        jdbcTemplate.update("update nodes set status = 'OFFLINE'");
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        String slug = "adf-" + UUID.randomUUID().toString().substring(0, 8);
        groupId = jdbcTemplate.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
    }

    @AfterEach
    void cleanUp() {
        for (Long nodeId : createdNodeIds) {
            jdbcTemplate.update("update vms set status = 'DELETED' where node_id = ?", nodeId);
            jdbcTemplate.update("update nodes set status = 'OFFLINE' where id = ?", nodeId);
        }
        createdNodeIds.clear();
    }

    @Test
    void missingVmFindingIsUpsertedBumpedAndAutoResolved() {
        long nodeId = createNode(wm.apiHost());
        long vmId = createVm(nodeId, 64001, "RUNNING", 1, 1024);
        stubClusterResources(wm); // empty cluster → missing

        reconciler.reconcile();

        Map<String, Object> finding = findingByDedupKey("MISSING_IN_PROXMOX", "vm:" + vmId);
        assertThat(finding.get("status")).isEqualTo("OPEN");
        assertThat((String) finding.get("summary")).contains("Proxmox에 VM 없음");
        assertThat(((Number) finding.get("vm_id")).longValue()).isEqualTo(vmId);
        assertThat(((Number) finding.get("proxmox_vmid")).intValue()).isEqualTo(64001);
        assertThat(finding.get("node_name")).isNotNull();
        Instant firstSeen = ts(finding.get("first_seen_at"));
        Instant lastSeen = ts(finding.get("last_seen_at"));

        // second cycle: same condition → same single row, last_seen_at bumped
        reconciler.reconcile();
        Map<String, Object> bumped = findingByDedupKey("MISSING_IN_PROXMOX", "vm:" + vmId);
        assertThat(countByDedupKey("MISSING_IN_PROXMOX", "vm:" + vmId)).isEqualTo(1);
        assertThat(bumped.get("id")).isEqualTo(finding.get("id"));
        assertThat(ts(bumped.get("first_seen_at"))).isEqualTo(firstSeen);
        assertThat(ts(bumped.get("last_seen_at"))).isAfterOrEqualTo(lastSeen);
        // the NEEDS_ADMIN parking behavior is unchanged
        assertThat(vmRepository.findById(vmId).orElseThrow().getStatus())
                .isEqualTo(VmStatus.NEEDS_ADMIN);

        // guest reappears → auto-resolved with resolved_by null
        wm.reset();
        stubClusterResources(wm, qemu(64001, "running", 1, 1024, "pickle"));
        reconciler.reconcile();
        Map<String, Object> resolved = findingByDedupKey("MISSING_IN_PROXMOX", "vm:" + vmId);
        assertThat(resolved.get("status")).isEqualTo("RESOLVED");
        assertThat(resolved.get("resolved_by")).isNull();
        assertThat(resolved.get("resolved_at")).isNotNull();
    }

    @Test
    void specMismatchAndUnmanagedGuestArePersistedAndAutoResolved() {
        long nodeId = createNode(wm.apiHost());
        long vmId = createVm(nodeId, 64011, "RUNNING", 2, 2048);
        stubClusterResources(wm,
                qemu(64011, "running", 4, 4096, "pickle"), // spec drift
                qemu(64999, "running", 2, 2048, "dev;pickle"), // unmanaged + tagged
                qemu(65000, "running", 2, 2048, "dev")); // untagged stranger → ignored

        reconciler.reconcile();

        Map<String, Object> spec = findingByDedupKey("SPEC_MISMATCH", "vm:" + vmId);
        assertThat(spec.get("status")).isEqualTo("OPEN");
        assertThat((String) spec.get("summary")).contains("사양 불일치");
        assertThat((String) spec.get("detail")).contains("\"expected\"").contains("\"actual\"");
        assertThat((String) spec.get("detail")).contains("\"vcpu\": 2").contains("\"vcpu\": 4");
        Map<String, Object> unmanaged = findingByDedupKey("UNMANAGED_GUEST", "vmid:64999");
        assertThat(unmanaged.get("status")).isEqualTo("OPEN");
        assertThat(unmanaged.get("vm_id")).isNull();
        assertThat(((Number) unmanaged.get("proxmox_vmid")).intValue()).isEqualTo(64999);
        assertThat((String) unmanaged.get("summary")).contains("미관리");
        assertThat(countByDedupKey("UNMANAGED_GUEST", "vmid:65000")).isZero();

        // both conditions disappear → both auto-resolve (pve1 is OFFLINE =
        // scope-excluded; the wiremock node listing succeeded)
        wm.reset();
        stubClusterResources(wm, qemu(64011, "running", 2, 2048, "pickle"));
        reconciler.reconcile();
        assertThat(findingByDedupKey("SPEC_MISMATCH", "vm:" + vmId).get("status"))
                .isEqualTo("RESOLVED");
        assertThat(findingByDedupKey("UNMANAGED_GUEST", "vmid:64999").get("status"))
                .isEqualTo("RESOLVED");
    }

    @Test
    void listingFailureHoldsFindingsInsteadOfAutoResolving() {
        long nodeId = createNode(wm.apiHost());
        long vmId = createVm(nodeId, 64021, "RUNNING", 1, 1024);
        stubClusterResources(wm); // missing → OPEN finding
        reconciler.reconcile();
        assertThat(findingByDedupKey("MISSING_IN_PROXMOX", "vm:" + vmId).get("status"))
                .isEqualTo("OPEN");

        // node listing now fails → the finding must stay OPEN, not auto-resolve
        wm.reset(); // no stub → request fails
        reconciler.reconcile();
        assertThat(findingByDedupKey("MISSING_IN_PROXMOX", "vm:" + vmId).get("status"))
                .isEqualTo("OPEN");
    }

    @Test
    void listDefaultsToOpenAndFiltersByKindAndIsSysAdminOnly() throws Exception {
        long openId = insertFinding("UNMANAGED_GUEST", "vmid:70001", "OPEN");
        long resolvedId = insertFinding("UNMANAGED_GUEST", "vmid:70002", "RESOLVED");

        mockMvc.perform(get("/api/v1/admin/drift-findings")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/drift-findings")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(openId)).exists())
                .andExpect(jsonPath(byId(resolvedId)).doesNotExist());

        mockMvc.perform(get("/api/v1/admin/drift-findings?status=RESOLVED")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(resolvedId)).exists())
                .andExpect(jsonPath(byId(openId)).doesNotExist());

        mockMvc.perform(get("/api/v1/admin/drift-findings?kind=MISSING_IN_PROXMOX")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(openId)).doesNotExist());
    }

    @Test
    void resolveIsCasAnsweres409WhenAlreadyResolvedAnd404WhenUnknown() throws Exception {
        long findingId = insertFinding("UNMANAGED_GUEST", "vmid:70011", "OPEN");

        mockMvc.perform(post("/api/v1/admin/drift-findings/{id}/resolve", findingId)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"잔여 게스트를 수동 정리했습니다.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(findingId))
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolvedById").isNumber())
                .andExpect(jsonPath("$.resolvedByEmail").value(SeedFixtures.SYSADMIN_EMAIL))
                .andExpect(jsonPath("$.resolutionNote").value("잔여 게스트를 수동 정리했습니다."));

        // manual resolve is audited
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'drift.resolve' and target_id = ?",
                Long.class, findingId)).isEqualTo(1);

        // second resolve → CAS finds 0 rows → 409
        mockMvc.perform(post("/api/v1/admin/drift-findings/{id}/resolve", findingId)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRIFT_FINDING_ALREADY_RESOLVED"));

        // unknown finding → 404
        mockMvc.perform(post("/api/v1/admin/drift-findings/999999/resolve")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        // over-long note → 422 before any state change
        long another = insertFinding("UNMANAGED_GUEST", "vmid:70012", "OPEN");
        mockMvc.perform(post("/api/v1/admin/drift-findings/{id}/resolve", another)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"" + "가".repeat(2001) + "\"}"))
                .andExpect(status().isUnprocessableContent());
        assertThat(findingByDedupKey("UNMANAGED_GUEST", "vmid:70012").get("status"))
                .isEqualTo("OPEN");
    }

    // --- fixtures ---------------------------------------------------------------

    /** Same shape as VmStatusPollerTest.stubClusterResources (package-private there). */
    private static void stubClusterResources(ProxmoxWireMockSupport wm, String... entries) {
        wm.server().stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .get(urlPathEqualTo("/api2/json/cluster/resources"))
                .withQueryParam("type", equalTo("vm"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":[" + String.join(",", entries) + "]}")));
    }

    /** One qemu entry in the real capture's shape; {@code memoryMb} → maxmem bytes. */
    private static String qemu(int vmid, String status, int maxcpu, long memoryMb, String tags) {
        String tagsField = tags == null ? "" : ",\"tags\":\"%s\"".formatted(tags);
        return ("{\"vmid\":%d,\"name\":\"vm-%d\",\"status\":\"%s\",\"type\":\"qemu\","
                + "\"node\":\"pve1\",\"maxmem\":%d,\"maxcpu\":%d,\"maxdisk\":4831838208,"
                + "\"template\":0%s}")
                .formatted(vmid, vmid, status, memoryMb * 1024 * 1024, maxcpu, tagsField);
    }

    private static String byId(long findingId) {
        return "$.content[?(@.id == %d)]".formatted(findingId);
    }

    /** Enum/jsonb columns cast to text so plain JDBC types come back. */
    private Map<String, Object> findingByDedupKey(String kind, String dedupKey) {
        return jdbcTemplate.queryForMap("""
                select id, status::text as status, summary, detail::text as detail, vm_id,
                       proxmox_vmid, node_name, first_seen_at, last_seen_at, resolved_at,
                       resolved_by, resolution_note
                  from drift_findings
                 where kind = ?::drift_finding_kind and dedup_key = ?
                 order by id desc limit 1
                """, kind, dedupKey);
    }

    /** timestamptz arrives as Timestamp or OffsetDateTime depending on driver path. */
    private static Instant ts(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new IllegalArgumentException("unexpected timestamp type: " + value);
    }

    private long countByDedupKey(String kind, String dedupKey) {
        return jdbcTemplate.queryForObject(
                "select count(*) from drift_findings where kind = ?::drift_finding_kind"
                        + " and dedup_key = ?", Long.class, kind, dedupKey);
    }

    private long insertFinding(String kind, String dedupKey, String status) {
        return jdbcTemplate.queryForObject("""
                insert into drift_findings (kind, proxmox_vmid, node_name, summary, status,
                                            dedup_key, resolved_at)
                values (?::drift_finding_kind, 70000, 'pve1', '테스트 드리프트 발견',
                        ?::drift_finding_status, ?,
                        case when ? = 'RESOLVED' then now() end)
                returning id
                """, Long.class, kind, status, dedupKey, status);
    }

    private long createNode(String apiHost) {
        String name = "adf-node-" + UUID.randomUUID().toString().substring(0, 8);
        long id = jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, status, cpu_threads, memory_mb, vm_bridge, storage)
                values (?, ?, 'MAINTENANCE', 16, 32768, 'vmbr2', 'local-lvm')
                returning id
                """, Long.class, name, apiHost);
        createdNodeIds.add(id);
        return id;
    }

    private long createVm(long nodeId, int proxmoxVmid, String status, int vcpu, int memoryMb) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         need_ssh, need_http, need_public)
                values (?, ?, ?, '드리프트 리포트 테스트', ?, ?, ?, 10, true, false, false)
                returning id
                """, Long.class, groupId, orgId, requesterId, templateId, vcpu, memoryMb);
        String hostname = "adf-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 10, ?, ?::vm_status)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                templateId, vcpu, memoryMb, proxmoxVmid, status);
    }
}
