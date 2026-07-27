package kr.ac.pusan.pickle.provisioning;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.ac.pusan.pickle.support.ProxmoxWireMockSupport.okFixture;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ProxmoxWireMockSupport;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Expiry sweep, driven directly against a fixed KST clock: notice-stage
 * ladder (smallest covering stage, late-VM skip-to-current, D-1 HIGH),
 * hourly-rerun idempotence (stage CAS + dedup key), the auto-stop boundary
 * (end date is inclusive — stops start the day after), claim-conflict skip,
 * scheduled-deletion exclusion, the ACPI → force-stop fallback pipeline, and
 * post-claim eligibility re-validation. The JobRunr server is off; the worker
 * is invoked directly like the other job tests.
 */
@SpringBootTest(properties = {
        "pickle.proxmox.token-id=pickle@pve!test",
        "pickle.proxmox.token-secret=wiremock-test-secret",
        "jobrunr.background-job-server.enabled=false"})
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, VmExpiryJobTest.FixedClockConfig.class})
class VmExpiryJobTest {

    /** 2026-03-10 12:00 KST — the default "today" for every date computation. */
    private static final Instant FIXED_NOW = Instant.parse("2026-03-10T03:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);

    /** Settable fixed clock: boundary tests move it, setUp resets it. */
    static final class MutableClock extends Clock {
        private volatile Instant instant = FIXED_NOW;

        void set(Instant value) {
            instant = value;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return new MutableClock();
        }
    }

    private static final AtomicInteger VMID_SEQ = new AtomicInteger(940_000);

    private static final String SHUTDOWN_UPID =
            "UPID:pve1:0006DC5F:0054AA57:6A4E2D01:qmshutdown:102:pickle@pve!pickle-api:";
    private static final String STOP_UPID =
            "UPID:pve1:0006E033:0054C3AA:6A4E2D42:qmstop:102:pickle@pve!pickle-api:";

    private static ProxmoxWireMockSupport wm;

    @Autowired
    private Clock clock;

    @Autowired
    private VmExpiryJob vmExpiryJob;

    @Autowired
    private ExpiryStopJob expiryStopJob;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private long templateId;
    private long requesterId;
    private long groupId;
    private long nodeId;
    private String nodeName;
    private long ownerId;
    private long editorId;
    private long memberId;

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
        ((MutableClock) clock).set(FIXED_NOW);
        wm.reset();
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        String slug = "vexp-" + UUID.randomUUID().toString().substring(0, 8);
        groupId = jdbcTemplate.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
        ownerId = createUser("owner." + slug + "@pusan.ac.kr");
        editorId = createUser("manager." + slug + "@pusan.ac.kr");
        memberId = createUser("member." + slug + "@pusan.ac.kr");
        addMember(ownerId, "OWNER");
        addMember(editorId, "EDITOR");
        addMember(memberId, "MEMBER");
        nodeName = "wmexp-" + UUID.randomUUID().toString().substring(0, 8);
        nodeId = jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, status, cpu_threads, memory_mb, vm_bridge, storage)
                values (?, ?, 'MAINTENANCE', 16, 32768, 'vmbr2', 'local-lvm')
                returning id
                """, Long.class, nodeName, wm.apiHost());
    }

    // ── notices ────────────────────────────────────────────────────────────

    @Test
    void noticeLadderSelectsSmallestCoveringStageAndRerunsSendNothing() {
        long vm14 = createVm("STOPPED", TODAY.plusDays(14));
        long vm7 = createVm("STOPPED", TODAY.plusDays(7));
        long vm1 = createVm("RUNNING", TODAY.plusDays(1));
        long vmLate = createVm("STOPPED", TODAY.plusDays(5)); // created past D-7 → skips to D-7
        long vmNoDate = createVm("STOPPED", null);
        long vmScheduled = createVm("STOPPED", TODAY.plusDays(3));
        jdbcTemplate.update("update vms set delete_scheduled_for = now() where id = ?", vmScheduled);

        vmExpiryJob.run();

        assertThat(noticeEvents(vm14)).containsExactly("vm.expiry.d14");
        assertThat(noticeEvents(vm7)).containsExactly("vm.expiry.d7");
        assertThat(noticeEvents(vm1)).containsExactly("vm.expiry.d1");
        assertThat(noticeEvents(vmLate)).containsExactly("vm.expiry.d7"); // skip-to-current
        assertThat(noticeCount(vmNoDate)).isZero();
        assertThat(noticeCount(vmScheduled)).isZero();
        assertThat(stageOf(vm14)).isEqualTo(14);
        assertThat(stageOf(vmLate)).isEqualTo(7);

        // recipients: OWNER + EDITOR, never the plain MEMBER; D-1 is HIGH
        assertThat(recipientsOf(vm1)).containsExactlyInAnyOrder(ownerId, editorId);
        assertThat(jdbcTemplate.queryForObject("""
                select distinct importance from notifications
                 where event = 'vm.expiry.d1' and payload ->> 'vmId' = ?
                """, String.class, String.valueOf(vm1))).isEqualTo("HIGH");
        assertThat(jdbcTemplate.queryForObject("""
                select distinct importance from notifications
                 where event = 'vm.expiry.d14' and payload ->> 'vmId' = ?
                """, String.class, String.valueOf(vm14))).isEqualTo("NORMAL");

        // hourly re-run: the stage CAS + dedup key make it a complete no-op
        long before = totalNoticeRows();
        vmExpiryJob.run();
        assertThat(totalNoticeRows()).isEqualTo(before);
        assertThat(stageOf(vm14)).isEqualTo(14);

        // extension re-arm (e2e): the admin extends vm1 → markers cleared →
        // the next sweep sends a FRESH notice for the NEW end date (the dedup
        // key embeds endDate, so the old D-1 row does not block it)
        assertThat(vmRepository.updatePeriod(vm1, null, TODAY.plusDays(7),
                List.of(VmStatus.DELETED, VmStatus.DELETING), Instant.now())).isEqualTo(1);
        assertThat(stageOf(vm1)).isNull();
        vmExpiryJob.run();
        assertThat(noticeEvents(vm1)).containsExactlyInAnyOrder("vm.expiry.d1", "vm.expiry.d7");
        assertThat(stageOf(vm1)).isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject("""
                select count(distinct payload ->> 'endDate') from notifications
                 where event like 'vm.expiry.d%' and payload ->> 'vmId' = ?
                """, Long.class, String.valueOf(vm1))).isEqualTo(2);
    }

    // ── auto-stop ──────────────────────────────────────────────────────────

    @Test
    void autoStopClaimsOnlyPastEndDateAndTheWorkerForceStopFallbackConverges() {
        int vmid = VMID_SEQ.incrementAndGet();
        long vmExpired = createVm("RUNNING", TODAY.minusDays(1), vmid);
        long vmEndsToday = createVm("RUNNING", TODAY); // inclusive end → not today

        stubGuestRunning(vmid);
        // ACPI shutdown times out (real capture 61) → force stop (capture 63)
        wm.server().stubFor(WireMock.post(urlPathEqualTo(qemuPath(vmid, "status/shutdown")))
                .willReturn(okFixture("61-shutdown")));
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(SHUTDOWN_UPID)))
                .willReturn(okFixture("61-shutdown-status")));
        wm.server().stubFor(WireMock.post(urlPathEqualTo(qemuPath(vmid, "status/stop")))
                .willReturn(okFixture("63-stop")));
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(STOP_UPID)))
                .willReturn(okFixture("63-stop-status")));

        vmExpiryJob.run();

        // boundary: endDate is inclusive — the VM ending today is untouched
        assertThat(pendingActionOf(vmEndsToday)).isNull();
        assertThat(pendingActionOf(vmExpired)).isEqualTo("EXPIRE_STOP");

        // the enqueued worker (JobRunr server off → invoked directly)
        expiryStopJob.stop(vmExpired);

        assertThat(statusOf(vmExpired)).isEqualTo("STOPPED");
        assertThat(column(vmExpired, "expiry_stopped_at")).isNotNull();
        assertThat(jdbcTemplate.queryForObject("select status_detail from vms where id = ?",
                String.class, vmExpired)).isEqualTo(ExpiryStopJob.DETAIL_EXPIRY_STOPPED);
        assertThat(pendingActionOf(vmExpired)).isNull(); // claim released
        // permanent history: EXPIRE_STOP with a null actor (system action)
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from vm_events
                 where vm_id = ? and type = 'EXPIRE_STOP' and actor_id is null
                """, Long.class, vmExpired)).isEqualTo(1);
        // HIGH notification to OWNER/EDITOR + the org's ORG_ADMINs
        assertThat(jdbcTemplate.queryForList("""
                select user_id from notifications
                 where event = 'vm.expiry.stopped' and payload ->> 'vmId' = ?
                """, Long.class, String.valueOf(vmExpired)))
                .contains(ownerId, editorId, requesterId)
                .doesNotContain(memberId);
        wm.server().verify(postRequestedFor(urlPathEqualTo(qemuPath(vmid, "status/shutdown"))));
        wm.server().verify(postRequestedFor(urlPathEqualTo(qemuPath(vmid, "status/stop"))));

        // re-running the sweep finds nothing left to stop
        vmExpiryJob.run();
        assertThat(pendingActionOf(vmExpired)).isNull();
    }

    @Test
    void busyPowerSlotAndDisabledSettingBothSkipTheStop() {
        long vmBusy = createVm("RUNNING", TODAY.minusDays(2));
        jdbcTemplate.update("update vms set pending_power_action = 'START',"
                + " pending_power_action_at = now() where id = ?", vmBusy);

        vmExpiryJob.run();
        assertThat(pendingActionOf(vmBusy)).isEqualTo("START"); // claim lost → skipped

        jdbcTemplate.update("update vms set pending_power_action = null,"
                + " pending_power_action_at = null where id = ?", vmBusy);
        jdbcTemplate.update(
                "update settings set value = 'false'::jsonb where key = 'vm_expiry_autostop_enabled'");
        try {
            vmExpiryJob.run();
            assertThat(pendingActionOf(vmBusy)).isNull(); // kill switch honored
        } finally {
            jdbcTemplate.update("update settings set value = 'true'::jsonb"
                    + " where key = 'vm_expiry_autostop_enabled'");
        }
    }

    @Test
    void autoStopBoundaryFollowsKstNotUtc() {
        long vmId = createVm("RUNNING", LocalDate.of(2026, 3, 10));

        // 2026-03-09 23:00 UTC = 03-10 08:00 KST → end date (inclusive) not
        // yet past in KST → no claim
        ((MutableClock) clock).set(Instant.parse("2026-03-09T23:00:00Z"));
        vmExpiryJob.run();
        assertThat(pendingActionOf(vmId)).isNull();

        // 2026-03-10 15:30 UTC = 03-11 00:30 KST → past in KST although the
        // UTC calendar date is still 03-10 — a todayKst→UTC regression would
        // skip this claim
        ((MutableClock) clock).set(Instant.parse("2026-03-10T15:30:00Z"));
        vmExpiryJob.run();
        assertThat(pendingActionOf(vmId)).isEqualTo("EXPIRE_STOP");
    }

    @Test
    void autoStopExcludesDeletionBoundVms() {
        long scheduled = createVm("RUNNING", TODAY.minusDays(1));
        long accepted = createVm("RUNNING", TODAY.minusDays(1));
        jdbcTemplate.update("update vms set delete_scheduled_for = now() where id = ?", scheduled);
        jdbcTemplate.update("update vms set delete_requested_at = now() where id = ?", accepted);

        vmExpiryJob.run();

        // the deletion flow owns these VMs — the expiry sweep must not race it
        assertThat(pendingActionOf(scheduled)).isNull();
        assertThat(pendingActionOf(accepted)).isNull();
    }

    @Test
    void workerRevalidatesEligibilityAfterTheClaim() {
        long vmId = createVm("RUNNING", TODAY.minusDays(1));
        // claim as the sweep would, then extend the period before the worker runs
        jdbcTemplate.update("update vms set pending_power_action = 'EXPIRE_STOP',"
                + " pending_power_action_at = now() where id = ?", vmId);
        jdbcTemplate.update("update vms set end_date = ? where id = ?", TODAY.plusDays(30), vmId);

        expiryStopJob.stop(vmId);

        assertThat(statusOf(vmId)).isEqualTo("RUNNING"); // no longer eligible
        assertThat(column(vmId, "expiry_stopped_at")).isNull();
        assertThat(pendingActionOf(vmId)).isNull(); // claim still released
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private long createUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", "만료테스트");
            user.setRole(UserRole.USER);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        }).getId();
    }

    private void addMember(long userId, String role) {
        jdbcTemplate.update("""
                insert into group_members (group_id, user_id, role)
                values (?, ?, ?::group_member_role)
                """, groupId, userId, role);
    }

    private long createVm(String status, LocalDate endDate) {
        return createVm(status, endDate, VMID_SEQ.incrementAndGet());
    }

    private long createVm(String status, LocalDate endDate, int proxmoxVmid) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '만료 테스트', ?, 2, 2048, 10)
                returning id
                """, Long.class, groupId, orgId, requesterId, templateId);
        String hostname = "vexp-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status,
                                 end_date)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, ?, ?::vm_status, ?)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                templateId, proxmoxVmid, status, endDate);
    }

    private void stubGuestRunning(int vmid) {
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("""
                                {"data":[{"vmid":%d,"type":"qemu","status":"running",
                                          "node":"%s","name":"test","tags":"pickle"}]}
                                """.formatted(vmid, nodeName))));
    }

    private String qemuPath(int vmid, String suffix) {
        return "/api2/json/nodes/" + nodeName + "/qemu/" + vmid + "/" + suffix;
    }

    private String taskStatusPath(String upid) {
        return "/api2/json/nodes/" + nodeName + "/tasks/" + upid + "/status";
    }

    private List<String> noticeEvents(long vmId) {
        return jdbcTemplate.queryForList("""
                select distinct event from notifications
                 where event like 'vm.expiry.d%' and payload ->> 'vmId' = ?
                """, String.class, String.valueOf(vmId));
    }

    private long noticeCount(long vmId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from notifications
                 where event like 'vm.expiry.%' and payload ->> 'vmId' = ?
                """, Long.class, String.valueOf(vmId));
    }

    private List<Long> recipientsOf(long vmId) {
        return jdbcTemplate.queryForList("""
                select user_id from notifications
                 where event like 'vm.expiry.d%' and payload ->> 'vmId' = ?
                """, Long.class, String.valueOf(vmId));
    }

    private long totalNoticeRows() {
        return jdbcTemplate.queryForObject(
                "select count(*) from notifications where event like 'vm.expiry.d%'", Long.class);
    }

    private Integer stageOf(long vmId) {
        return jdbcTemplate.queryForObject(
                "select last_expiry_notice_stage from vms where id = ?", Integer.class, vmId);
    }

    private String statusOf(long vmId) {
        return jdbcTemplate.queryForObject("select status::text from vms where id = ?",
                String.class, vmId);
    }

    private String pendingActionOf(long vmId) {
        return jdbcTemplate.queryForObject("select pending_power_action from vms where id = ?",
                String.class, vmId);
    }

    private Object column(long vmId, String column) {
        return jdbcTemplate.queryForObject(
                "select " + column + " from vms where id = ?", Object.class, vmId);
    }
}
