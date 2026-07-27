package kr.ac.pusan.pickle.vm;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static kr.ac.pusan.pickle.support.ProxmoxWireMockSupport.okFixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MockMailSender;
import kr.ac.pusan.pickle.provisioning.DeleteVmJob;
import kr.ac.pusan.pickle.provisioning.DeletionSweeper;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.support.ProxmoxWireMockSupport;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * Deletion flows per contract v0.3.1: self-delete grace scheduling (168 h)
 * with the backup/cancellation-policy mails, the ERROR immediate path,
 * admin scheduled deletes (min-notice 422), kind-aware admin cancellation,
 * name-confirmed force deletes, the {@link DeleteVmJob} destroy pipeline
 * against real pve1 captures (incl. the ACPI-timeout → force-stop fallback,
 * fixture 61), retry-then-park, and the sweeper's due selection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmDeletionTest {

    private static final String NODE_NAME = "wm-delete";

    private static final AtomicInteger VMID_SEQ = new AtomicInteger(920_000);
    private static final AtomicInteger IP_SEQ = new AtomicInteger(0);

    private static final String SHUTDOWN_UPID =
            "UPID:pve1:0006DC5F:0054AA57:6A4E2D01:qmshutdown:102:pickle@pve!pickle-api:";
    private static final String STOP_UPID =
            "UPID:pve1:0006E033:0054C3AA:6A4E2D42:qmstop:102:pickle@pve!pickle-api:";
    private static final String DELETE_UPID =
            "UPID:pve1:0006E08C:0054C47B:6A4E2D44:qmdestroy:102:pickle@pve!pickle-api:";

    private static ProxmoxWireMockSupport wm;

    @DynamicPropertySource
    static void proxmoxProperties(DynamicPropertyRegistry registry) {
        registry.add("pickle.proxmox.token-id", () -> "pickle@pve!pickle-api");
        registry.add("pickle.proxmox.token-secret", () -> "wiremock-test-secret");
        registry.add("pickle.proxmox.task-poll-interval", () -> "50ms");
        registry.add("pickle.proxmox.task-poll-timeout", () -> "5s");
    }

    @BeforeAll
    static void startServer() {
        wm = ProxmoxWireMockSupport.start();
    }

    @AfterAll
    static void stopServer() {
        wm.close();
    }

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
    private DeleteVmJob deleteVmJob;

    @Autowired
    private DeletionSweeper deletionSweeper;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private MockMailSender mockMailSender;

    @Autowired
    private kr.ac.pusan.pickle.notification.NotificationDispatchJob notificationDispatchJob;

    private User owner;
    private User member;
    private User outsider;
    private String ownerToken;
    private String memberToken;
    private String outsiderToken;
    private String sysAdminToken;
    private String orgAdminToken;
    private long orgId;
    private long nodeId;
    private long templateId;
    private long groupId;
    private long poolId;
    private int proxmoxVmid;

    @BeforeEach
    void setUp() throws Exception {
        wm.reset();
        mockMailSender.clear();
        owner = ensureUser("vmdel.owner@pusan.ac.kr", "삭제소유자");
        member = ensureUser("vmdel.member@pusan.ac.kr", "삭제멤버");
        outsider = ensureUser("vmdel.outsider@pusan.ac.kr", "삭제외부인");
        ownerToken = jwtService.createAccessToken(owner);
        memberToken = jwtService.createAccessToken(member);
        outsiderToken = jwtService.createAccessToken(outsider);
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        templateId = jdbcTemplate.queryForObject("select min(id) from vm_templates", Long.class);
        poolId = jdbcTemplate.queryForObject(
                "select id from ip_pools where name = 'guest-private'", Long.class);
        nodeId = ensureWireMockNode();
        groupId = createTeam("vmdel-" + UUID.randomUUID().toString().substring(0, 8));
        addMember(groupId, member.getEmail(), "MEMBER");
    }

    // ── self-delete ────────────────────────────────────────────────────────

    @Test
    void selfDeleteSchedulesGraceShutdownAndMails() throws Exception {
        long vmId = createVm(VmStatus.RUNNING);
        Instant before = Instant.now();

        String body = mockMvc.perform(delete("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("SELF"))
                .andExpect(jsonPath("$.requestedById").value(owner.getId()))
                .andExpect(jsonPath("$.reason").value((Object) null))
                .andExpect(jsonPath("$.cancelable").value(true))
                .andReturn().getResponse().getContentAsString();

        // grace = settings.vm_delete_grace_hours (seeded 168 h = 7 days)
        Instant scheduledFor = Instant.parse(
                objectMapper.readTree(body).get("scheduledFor").asString());
        assertThat(scheduledFor).isAfterOrEqualTo(before.plus(Duration.ofHours(167)))
                .isBeforeOrEqualTo(Instant.now().plus(Duration.ofHours(169)));

        assertThat(statusOf(vmId)).isEqualTo("DELETING");
        assertThat(column(vmId, "delete_kind")).isEqualTo("SELF");
        assertThat(eventTypes(vmId)).contains("SELF_DELETE");
        assertThat(auditCount("vm.self_delete", vmId)).isEqualTo(1);

        // graceful-shutdown job enqueued after commit
        Long enqueued = jdbcTemplate.queryForObject(
                "select count(*) from jobrunr_jobs where jobsignature like '%DeleteVmJob.gracefulShutdown(%'",
                Long.class);
        assertThat(enqueued).isPositive();

        // notifications were inserted in-tx; the dispatcher emails them:
        // group members + org admins, with both policy notices
        notificationDispatchJob.dispatch();
        List<MailMessage> mails = mockMailSender.getMessages();
        assertThat(mails).extracting(MailMessage::to)
                .contains(owner.getEmail(), member.getEmail(), SeedFixtures.ORGADMIN_EMAIL);
        assertThat(mockMailSender.lastMessageTo(owner.getEmail()).body())
                .contains("플랫폼은 VM 데이터를 백업하지 않으며 삭제 후 복구할 수 없습니다")
                .contains("삭제 취소는 관리자만 가능합니다");

        // stacking another deletion on top → 409
        mockMvc.perform(delete("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"))
                .andExpect(jsonPath("$.detail").value("이미 삭제가 접수되었거나 진행 중인 VM입니다."));
    }

    @Test
    void selfDeleteAuthorizationAndStateGuards() throws Exception {
        long vmId = createVm(VmStatus.RUNNING);

        // MEMBER → 403 (owner-only), non-member → 404 (masked)
        mockMvc.perform(delete("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + memberToken)
                        .header(ReauthTestSupport.HEADER, reauth(memberToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        mockMvc.perform(delete("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + outsiderToken)
                        .header(ReauthTestSupport.HEADER, reauth(outsiderToken)))
                .andExpect(status().isNotFound());

        // ORG_ADMIN of another org → 404 (existence masked)
        mockMvc.perform(delete("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + otherOrgAdminToken())
                        .header(ReauthTestSupport.HEADER, reauth(otherOrgAdminToken())))
                .andExpect(status().isNotFound());

        // state guards: CREATING / NEEDS_ADMIN / DELETED → 409
        for (VmStatus status : List.of(VmStatus.CREATING, VmStatus.NEEDS_ADMIN, VmStatus.DELETED)) {
            setStatus(vmId, status);
            mockMvc.perform(delete("/api/v1/vms/" + vmId)
                            .header("Authorization", "Bearer " + ownerToken)
                            .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
        }

        // ORG_ADMIN of the VM's org may delete
        setStatus(vmId, VmStatus.STOPPED);
        mockMvc.perform(delete("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .header(ReauthTestSupport.HEADER, reauth(orgAdminToken)))
                .andExpect(status().isAccepted());
    }

    @Test
    void errorVmIsDeletedImmediatelyWithIpRelease() throws Exception {
        long vmId = createVm(VmStatus.ERROR);
        long allocationId = allocateIp(vmId);

        mockMvc.perform(delete("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("SELF"))
                .andExpect(jsonPath("$.cancelable").value(false));

        assertThat(statusOf(vmId)).isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject(
                "select deleted_at is not null from vms where id = ?", Boolean.class, vmId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "select status from ip_allocations where id = ?", String.class, allocationId))
                .isEqualTo("RELEASED");
        // acceptance + terminal purge event pair, both in the same tx
        assertThat(eventTypes(vmId)).contains("SELF_DELETE", "DELETE");
        assertThat(auditCount("vm.self_delete", vmId)).isEqualTo(1);
        // no pipeline: nothing enqueued for this VM
        Long tasks = jdbcTemplate.queryForObject(
                "select count(*) from provisioning_tasks where vm_id = ?", Long.class, vmId);
        assertThat(tasks).isZero();
    }

    // ── admin scheduled delete ─────────────────────────────────────────────

    @Test
    void adminScheduleDeleteEnforcesNoticeAndMailsReason() throws Exception {
        long vmId = createVm(VmStatus.RUNNING);

        // past instant → 422 with errors[] (the only remaining date rule —
        // the minimum-notice floor was dropped 2026-07-27)
        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/schedule-delete")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduledFor", Instant.now().minus(Duration.ofHours(1)).toString(),
                                "reason", "종료일 경과"))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("scheduledFor"));

        // within the recommended 7-day window is accepted now (warning is
        // console-side only)
        long shortNoticeVm = createVm(VmStatus.RUNNING);
        mockMvc.perform(post("/api/v1/admin/vms/" + shortNoticeVm + "/schedule-delete")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduledFor", Instant.now().plus(Duration.ofDays(1)).toString(),
                                "reason", "빠른 정리"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("ADMIN"));

        // blank reason → 422 (bean validation)
        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/schedule-delete")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduledFor", Instant.now().plus(Duration.ofDays(8)).toString(),
                                "reason", " "))))
                .andExpect(status().isUnprocessableContent());

        Instant scheduledFor = Instant.now().plus(Duration.ofDays(8));
        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/schedule-delete")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduledFor", scheduledFor.toString(),
                                "reason", "사용 종료일이 지난 VM 정리"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("ADMIN"))
                .andExpect(jsonPath("$.reason").value("사용 종료일이 지난 VM 정리"))
                .andExpect(jsonPath("$.cancelable").value(true));

        // the power state is untouched; intent + event + audit + user mail recorded
        assertThat(statusOf(vmId)).isEqualTo("RUNNING");
        assertThat(column(vmId, "delete_kind")).isEqualTo("ADMIN");
        assertThat(eventTypes(vmId)).contains("SCHEDULE_DELETE");
        assertThat(auditCount("vm.schedule_delete", vmId)).isEqualTo(1);
        notificationDispatchJob.dispatch();
        assertThat(mockMailSender.lastMessageTo(owner.getEmail()).body())
                .contains("사용 종료일이 지난 VM 정리")
                .contains("플랫폼은 VM 데이터를 백업하지 않으며 삭제 후 복구할 수 없습니다");

        // double-schedule and regular-user access → 409 / 403
        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/schedule-delete")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduledFor", scheduledFor.toString(), "reason", "중복"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/schedule-delete")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduledFor", scheduledFor.toString(), "reason", "학생"))))
                .andExpect(status().isForbidden());

        // self-delete grace in progress (DELETING) can not be re-scheduled
        long deletingVm = createVm(VmStatus.DELETING);
        mockMvc.perform(post("/api/v1/admin/vms/" + deletingVm + "/schedule-delete")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduledFor", scheduledFor.toString(), "reason", "유예 중"))))
                .andExpect(status().isConflict());
    }

    // ── admin cancel ───────────────────────────────────────────────────────

    @Test
    void cancelIsKindAwareAndAdminOnly() throws Exception {
        // SELF: pending grace → cancel returns the VM to STOPPED
        long selfVm = createVm(VmStatus.DELETING);
        markPendingDeletion(selfVm, "SELF", Instant.now().plus(Duration.ofDays(5)));
        mockMvc.perform(post("/api/v1/admin/vms/" + selfVm + "/cancel-scheduled-delete")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("삭제가 취소되었습니다."));
        assertThat(statusOf(selfVm)).isEqualTo("STOPPED");
        assertThat(column(selfVm, "delete_kind")).isNull();
        assertThat(eventTypes(selfVm)).contains("CANCEL_SCHEDULED_DELETE");
        assertThat(auditCount("vm.cancel_scheduled_delete", selfVm)).isEqualTo(1);
        notificationDispatchJob.dispatch();
        assertThat(mockMailSender.lastMessageTo(owner.getEmail()).body()).contains("취소");

        // ADMIN: schedule only — the power state is preserved
        long adminVm = createVm(VmStatus.RUNNING);
        markPendingDeletion(adminVm, "ADMIN", Instant.now().plus(Duration.ofDays(8)));
        mockMvc.perform(post("/api/v1/admin/vms/" + adminVm + "/cancel-scheduled-delete")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());
        assertThat(statusOf(adminVm)).isEqualTo("RUNNING");
        assertThat(column(adminVm, "delete_kind")).isNull();

        // FORCE / no pending deletion / grace elapsed → 409
        long forceVm = createVm(VmStatus.DELETING);
        markPendingDeletion(forceVm, "FORCE", Instant.now());
        mockMvc.perform(post("/api/v1/admin/vms/" + forceVm + "/cancel-scheduled-delete")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
        long plainVm = createVm(VmStatus.RUNNING);
        mockMvc.perform(post("/api/v1/admin/vms/" + plainVm + "/cancel-scheduled-delete")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isConflict());
        long elapsedVm = createVm(VmStatus.DELETING);
        markPendingDeletion(elapsedVm, "SELF", Instant.now().minus(Duration.ofMinutes(1)));
        mockMvc.perform(post("/api/v1/admin/vms/" + elapsedVm + "/cancel-scheduled-delete")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isConflict());

        // users cannot cancel (403 via method security), other org → 404
        mockMvc.perform(post("/api/v1/admin/vms/" + adminVm + "/cancel-scheduled-delete")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
        markPendingDeletion(adminVm, "ADMIN", Instant.now().plus(Duration.ofDays(8)));
        mockMvc.perform(post("/api/v1/admin/vms/" + adminVm + "/cancel-scheduled-delete")
                        .header("Authorization", "Bearer " + otherOrgAdminToken()))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminCancelStaysValidUntilDestructionClaimsTheVm() throws Exception {
        // Past due but unswept: the VM is intact (the sweeper fires on a
        // 5-minute cadence), so cancel must still succeed — the wall clock
        // alone must not strand an intact VM in an uncancelable state.
        long dueVm = createVm(VmStatus.RUNNING);
        markPendingDeletion(dueVm, "ADMIN", Instant.now().minus(Duration.ofMinutes(3)));
        mockMvc.perform(post("/api/v1/admin/vms/" + dueVm + "/cancel-scheduled-delete")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());
        assertThat(statusOf(dueVm)).isEqualTo("RUNNING");
        assertThat(column(dueVm, "delete_kind")).isNull();

        // Once the destroy pipeline claimed the VM (DELETING), cancel refuses.
        long claimedVm = createVm(VmStatus.DELETING);
        markPendingDeletion(claimedVm, "ADMIN", Instant.now().minus(Duration.ofMinutes(3)));
        mockMvc.perform(post("/api/v1/admin/vms/" + claimedVm + "/cancel-scheduled-delete")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
    }

    @Test
    void staleDestroyJobNeverExecutesAFutureIntent() {
        // Cancel → re-schedule inside one JobRunr poll interval: the stale
        // enqueued job sees a fresh ADMIN intent, but its schedule lies in the
        // future — the destroy claim must refuse (not destroy 7 days early).
        long adminVm = createVm(VmStatus.RUNNING);
        markPendingDeletion(adminVm, "ADMIN", Instant.now().plus(Duration.ofDays(7)));
        deleteVmJob.deleteVm(adminVm);
        assertThat(statusOf(adminVm)).isEqualTo("RUNNING");
        assertThat(column(adminVm, "delete_kind")).isEqualTo("ADMIN");

        // Cancel → fresh SELF grace: the VM is already DELETING, so the claim
        // branch is skipped — the post-claim re-read must abort on the future
        // schedule instead of wiping the whole grace window.
        long selfVm = createVm(VmStatus.DELETING);
        markPendingDeletion(selfVm, "SELF", Instant.now().plus(Duration.ofDays(7)));
        deleteVmJob.deleteVm(selfVm);
        assertThat(statusOf(selfVm)).isEqualTo("DELETING");
        assertThat(column(selfVm, "delete_kind")).isEqualTo("SELF");
        assertThat(eventTypes(selfVm)).doesNotContain("DELETE");
    }

    // ── force delete ───────────────────────────────────────────────────────

    @Test
    void forceDeleteConfirmsNameAuditsAndEnqueues() throws Exception {
        long vmId = createVm(VmStatus.RUNNING);
        String vmName = jdbcTemplate.queryForObject("select name from vms where id = ?",
                String.class, vmId);

        // ORG_ADMIN → 403 (SYS_ADMIN only)
        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/force-delete")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("confirmName", vmName))))
                .andExpect(status().isForbidden());

        // wrong confirm name → 409 VM_CONFIRM_NAME_MISMATCH
        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/force-delete")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("confirmName", "wrong-name"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_CONFIRM_NAME_MISMATCH"));

        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/force-delete")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("confirmName", vmName))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value(
                        "강제 삭제를 접수했습니다. VM이 즉시 강제 종료되고 파기됩니다."));

        assertThat(statusOf(vmId)).isEqualTo("DELETING");
        assertThat(column(vmId, "delete_kind")).isEqualTo("FORCE");
        assertThat(eventTypes(vmId)).contains("FORCE_DELETE");
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'vm.force_delete' and target_id = ?",
                Long.class, vmId);
        assertThat(audits).isEqualTo(1);
        Long enqueued = jdbcTemplate.queryForObject(
                "select count(*) from jobrunr_jobs where jobsignature like '%DeleteVmJob.deleteVm(%'",
                Long.class);
        assertThat(enqueued).isPositive();
        notificationDispatchJob.dispatch();
        assertThat(mockMailSender.lastMessageTo(owner.getEmail()).body()).contains("관리자");

        // already destroyed → 409
        setStatus(vmId, VmStatus.DELETED);
        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/force-delete")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("confirmName", vmName))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
    }

    @Test
    void forceDeleteParksLiveProvisionTask() throws Exception {
        long vmId = createVm(VmStatus.CREATING);
        jdbcTemplate.update("""
                insert into provisioning_tasks (vm_id, kind, current_step, status, attempts)
                values (?, 'PROVISION', 5, 'RETRYING', 2)
                """, vmId);
        String vmName = jdbcTemplate.queryForObject("select name from vms where id = ?",
                String.class, vmId);

        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/force-delete")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("confirmName", vmName))))
                .andExpect(status().isAccepted());

        // the pipeline task is closed, so its backoff retry can never resume
        assertThat(jdbcTemplate.queryForObject("""
                select status from provisioning_tasks
                 where vm_id = ? and kind = 'PROVISION' order by id desc limit 1
                """, String.class, vmId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("""
                select last_error from provisioning_tasks
                 where vm_id = ? and kind = 'PROVISION' order by id desc limit 1
                """, String.class, vmId)).contains("강제 삭제");
        assertThat(statusOf(vmId)).isEqualTo("DELETING");
    }

    // ── the destroy pipeline (WireMock, real pve1 captures) ───────────────

    @Test
    void deleteJobShutsDownWithForceFallbackDestroysAndReleases() {
        long vmId = createVm(VmStatus.DELETING);
        long allocationId = allocateIp(vmId);
        markPendingDeletion(vmId, "SELF", Instant.now().minus(Duration.ofMinutes(1)));
        // an unviewed plaintext initial password must not survive destruction
        jdbcTemplate.update(
                "update vms set password_enc = 'v1:pw-unviewed', password_hash = 'h'"
                        + " where id = ?", vmId);

        stubClusterResourcesRunning();
        // ACPI shutdown fails with the captured timeout exitstatus → force stop
        wm.server().stubFor(WireMock.post(urlPathEqualTo(qemuPath("status/shutdown")))
                .willReturn(okFixture("61-shutdown")));
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(SHUTDOWN_UPID)))
                .willReturn(okFixture("61-shutdown-status")));
        wm.server().stubFor(WireMock.post(urlPathEqualTo(qemuPath("status/stop")))
                .willReturn(okFixture("63-stop")));
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(STOP_UPID)))
                .willReturn(okFixture("63-stop-status")));
        wm.server().stubFor(WireMock.delete(urlPathEqualTo(qemuBasePath()))
                .willReturn(okFixture("70-delete")));
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(DELETE_UPID)))
                .willReturn(okFixture("70-delete-status")));
        stubConfigPut(proxmoxVmid); // always-on protection cleared before destroy

        deleteVmJob.deleteVm(vmId);

        assertThat(statusOf(vmId)).isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject("select deleted_at is not null from vms where id = ?",
                Boolean.class, vmId)).isTrue();
        // plaintext wiped, hash kept for support verification
        assertThat(column(vmId, "password_enc")).isNull();
        assertThat(column(vmId, "password_hash")).isEqualTo("h");
        assertThat(jdbcTemplate.queryForObject("select status from ip_allocations where id = ?",
                String.class, allocationId)).isEqualTo("RELEASED");
        assertThat(jdbcTemplate.queryForObject("""
                select status from provisioning_tasks where vm_id = ? and kind = 'DELETE'
                """, String.class, vmId)).isEqualTo("DONE");
        assertThat(eventTypes(vmId)).contains("DELETE");
        // graceful attempt used the 120 s guest timeout, destroy purged
        wm.server().verify(postRequestedFor(urlPathEqualTo(qemuPath("status/shutdown")))
                .withRequestBody(containing("timeout=120")));
        wm.server().verify(postRequestedFor(urlPathEqualTo(qemuPath("status/stop"))));
        wm.server().verify(deleteRequestedFor(urlPathEqualTo(qemuBasePath())));
        // the protection clear happened, and strictly before the destroy
        wm.server().verify(WireMock.putRequestedFor(urlPathEqualTo(qemuPath("config")))
                .withRequestBody(containing("protection=0")));
        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> events =
                new java.util.ArrayList<>(wm.server().getAllServeEvents());
        java.util.Collections.reverse(events); // journal is newest-first
        List<String> ordered = events.stream()
                .filter(event -> ("PUT".equals(event.getRequest().getMethod().getName())
                        && event.getRequest().getUrl().startsWith(qemuPath("config")))
                        || "DELETE".equals(event.getRequest().getMethod().getName()))
                .map(event -> event.getRequest().getMethod().getName())
                .toList();
        assertThat(ordered).containsExactly("PUT", "DELETE");
        // org admin notified of the final destruction
        notificationDispatchJob.dispatch();
        assertThat(mockMailSender.lastMessageTo(SeedFixtures.ORGADMIN_EMAIL).body()).contains("파기");
    }

    @Test
    void deleteJobParksOnForeignGuestAtRecycledVmid() {
        long vmId = createVm(VmStatus.DELETING);
        markPendingDeletion(vmId, "SELF", Instant.now().minus(Duration.ofMinutes(1)));
        // a foreign guest sits at our stored vmid: different name, no pickle tag
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("""
                                {"data":[{"vmid":%d,"type":"qemu","status":"running",
                                          "node":"%s","name":"somebody-elses-vm"}]}
                                """.formatted(proxmoxVmid, NODE_NAME))));

        deleteVmJob.deleteVm(vmId);

        // never shut down or destroyed; parked for an operator instead
        wm.server().verify(0, postRequestedFor(urlPathEqualTo(qemuPath("status/shutdown"))));
        wm.server().verify(0, deleteRequestedFor(urlPathEqualTo(qemuBasePath())));
        assertThat(taskState(vmId).get(0)).isEqualTo("NEEDS_ADMIN");
        assertThat(statusOf(vmId)).isEqualTo("DELETING");
        assertThat(statusDetailOf(vmId)).contains("파기 대상 불일치");
    }

    @Test
    void deleteJobRetriesWithBackoffThenParksNeedsAdmin() {
        long vmId = createVm(VmStatus.DELETING);
        markPendingDeletion(vmId, "SELF", Instant.now().minus(Duration.ofMinutes(1)));
        // every Proxmox call answers 500 → each attempt fails
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(aResponse().withStatus(500).withBody("{\"data\":null}")));

        deleteVmJob.deleteVm(vmId);
        assertThat(taskState(vmId)).containsExactly("RETRYING", 1);
        Long retryScheduled = jdbcTemplate.queryForObject(
                "select count(*) from jobrunr_jobs where jobsignature like '%DeleteVmJob.deleteVm(%'",
                Long.class);
        assertThat(retryScheduled).isPositive();

        deleteVmJob.deleteVm(vmId);
        assertThat(taskState(vmId)).containsExactly("RETRYING", 2);

        deleteVmJob.deleteVm(vmId);
        assertThat(taskState(vmId)).containsExactly("NEEDS_ADMIN", 3);
        // the VM stays DELETING for the operator; a further run is a no-op
        assertThat(statusOf(vmId)).isEqualTo("DELETING");
        assertThat(statusDetailOf(vmId)).contains("관리자");
        deleteVmJob.deleteVm(vmId);
        assertThat(taskState(vmId)).containsExactly("NEEDS_ADMIN", 3);
    }

    @Test
    void deleteJobClosesTaskAndSkipsDestroyWhenCancellationRaced() {
        // state a raced admin cancel leaves behind: deletion intent cleared,
        // VM back to STOPPED, but a live DELETE task still pending
        long vmId = createVm(VmStatus.STOPPED);
        jdbcTemplate.update("""
                insert into provisioning_tasks (vm_id, kind, current_step, status, attempts)
                values (?, 'DELETE', 0, 'PENDING', 0)
                """, vmId);

        deleteVmJob.deleteVm(vmId);

        // no Proxmox call was made and the orphaned task is closed
        assertThat(wm.server().getAllServeEvents()).isEmpty();
        assertThat(statusOf(vmId)).isEqualTo("STOPPED");
        assertThat(taskState(vmId)).containsExactly("FAILED", 0);
    }

    // ── sweeper ────────────────────────────────────────────────────────────

    @Test
    void sweeperSelectsOnlyDuePendingDeletions() {
        long dueSelf = createVm(VmStatus.DELETING);
        markPendingDeletion(dueSelf, "SELF", Instant.now().minus(Duration.ofMinutes(5)));
        long futureSelf = createVm(VmStatus.DELETING);
        markPendingDeletion(futureSelf, "SELF", Instant.now().plus(Duration.ofDays(6)));
        long dueAdmin = createVm(VmStatus.RUNNING);
        markPendingDeletion(dueAdmin, "ADMIN", Instant.now().minus(Duration.ofMinutes(5)));
        long parked = createVm(VmStatus.NEEDS_ADMIN);
        markPendingDeletion(parked, "SELF", Instant.now().minus(Duration.ofMinutes(5)));
        long plain = createVm(VmStatus.RUNNING);
        // isolate from pending deletions left over by other tests in this DB
        jdbcTemplate.update("""
                update vms set delete_scheduled_for = now() + interval '30 days'
                 where delete_scheduled_for <= now() and id not in (?, ?, ?, ?, ?)
                """, dueSelf, futureSelf, dueAdmin, parked, plain);

        List<Long> due = vmRepository.findDueForDeletion(Instant.now(),
                java.util.Set.of(VmStatus.DELETING, VmStatus.RUNNING, VmStatus.STOPPED,
                        VmStatus.REBOOTING))
                .stream().map(Vm::getId)
                .filter(id -> List.of(dueSelf, futureSelf, dueAdmin, parked, plain).contains(id))
                .toList();
        assertThat(due).containsExactly(dueSelf, dueAdmin);

        Long before = deleteJobCount();
        deletionSweeper.sweep();
        assertThat(deleteJobCount() - before).isEqualTo(2);
    }

    @Test
    void sweeperSkipsRetryingDeleteTaskInsideBackoffWindow() {
        long vmId = createVm(VmStatus.DELETING);
        markPendingDeletion(vmId, "SELF", Instant.now().minus(Duration.ofMinutes(5)));
        // a failed run left the task RETRYING moments ago (attempts 3 → 5 min backoff)
        jdbcTemplate.update("""
                insert into provisioning_tasks (vm_id, kind, current_step, status, attempts)
                values (?, 'DELETE', 0, 'RETRYING', 3)
                """, vmId);
        // isolate from pending deletions left over by other tests in this DB
        jdbcTemplate.update("""
                update vms set delete_scheduled_for = now() + interval '30 days'
                 where delete_scheduled_for <= now() and id <> ?
                """, vmId);

        Long before = deleteJobCount();
        deletionSweeper.sweep();
        // inside the backoff window the sweeper must not resume the task early
        assertThat(deleteJobCount() - before).isZero();

        // window elapsed (scheduled run lost) → the sweeper is the recovery
        jdbcTemplate.update(
                "update provisioning_tasks set updated_at = now() - interval '6 minutes'"
                        + " where vm_id = ?", vmId);
        deletionSweeper.sweep();
        assertThat(deleteJobCount() - before).isEqualTo(1);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private Long deleteJobCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from jobrunr_jobs where jobsignature like '%DeleteVmJob.deleteVm(%'",
                Long.class);
    }

    private Long auditCount(String action, long vmId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = ? and target_id = ?",
                Long.class, action, vmId);
    }

    private List<Object> taskState(long vmId) {
        return jdbcTemplate.queryForObject("""
                select status, attempts from provisioning_tasks
                 where vm_id = ? and kind = 'DELETE' order by id desc limit 1
                """, (rs, i) -> List.of(rs.getString(1), rs.getInt(2)), vmId);
    }

    private void stubClusterResourcesRunning() {
        // carries the pickle tag, as the config step sets on every managed guest
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("""
                                {"data":[{"vmid":%d,"type":"qemu","status":"running",
                                          "node":"%s","name":"test","tags":"pickle"}]}
                                """.formatted(proxmoxVmid, NODE_NAME))));
    }

    private String qemuBasePath() {
        return "/api2/json/nodes/" + NODE_NAME + "/qemu/" + proxmoxVmid;
    }

    private String qemuPath(String suffix) {
        return qemuBasePath() + "/" + suffix;
    }

    private static String taskStatusPath(String upid) {
        return "/api2/json/nodes/" + NODE_NAME + "/tasks/" + upid + "/status";
    }

    private String otherOrgAdminToken() {
        Long otherOrgId = jdbcTemplate.query("select id from orgs where slug = 'vmdel-other'",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (otherOrgId == null) {
            otherOrgId = jdbcTemplate.queryForObject("""
                    insert into orgs (name, slug) values ('삭제테스트 타기관', 'vmdel-other') returning id
                    """, Long.class);
        }
        User otherAdmin = ensureUser("vmdel.otheradmin@pusan.ac.kr", "타기관관리자");
        otherAdmin.setRole(UserRole.ORG_ADMIN);
        otherAdmin.setOrgId(otherOrgId);
        userRepository.save(otherAdmin);
        return jwtService.createAccessToken(otherAdmin);
    }

    private String statusOf(long vmId) {
        return jdbcTemplate.queryForObject("select status from vms where id = ?", String.class, vmId);
    }

    private String statusDetailOf(long vmId) {
        return jdbcTemplate.queryForObject("select status_detail from vms where id = ?",
                String.class, vmId);
    }

    private String column(long vmId, String column) {
        return jdbcTemplate.queryForObject(
                "select " + column + "::text from vms where id = ?", String.class, vmId);
    }

    private List<String> eventTypes(long vmId) {
        return jdbcTemplate.queryForList(
                "select type from vm_events where vm_id = ? order by id", String.class, vmId);
    }

    private void setStatus(long vmId, VmStatus status) {
        jdbcTemplate.update("update vms set status = ?::vm_status where id = ?", status.name(), vmId);
    }

    private void markPendingDeletion(long vmId, String kind, Instant scheduledFor) {
        jdbcTemplate.update("""
                update vms
                   set delete_kind = ?::vm_delete_kind, delete_scheduled_for = ?,
                       delete_requested_at = now(), delete_requested_by = ?
                 where id = ?
                """, kind, java.sql.Timestamp.from(scheduledFor), owner.getId(), vmId);
    }

    private long allocateIp(long vmId) {
        String ip = "172.29.77." + (IP_SEQ.incrementAndGet() % 250 + 1);
        long allocationId = jdbcTemplate.queryForObject("""
                insert into ip_allocations (pool_id, ip, vm_id, status)
                values (?, ?::inet, ?, 'ALLOCATED')
                on conflict (ip) do update set vm_id = excluded.vm_id, status = 'ALLOCATED',
                                               released_at = null
                returning id
                """, Long.class, poolId, ip, vmId);
        jdbcTemplate.update("update vms set ip_allocation_id = ? where id = ?", allocationId, vmId);
        return allocationId;
    }

    // ── deletion / stop protection ───────────────────────────────────────────

    @Test
    void deletePipelineParksNeedsAdminOnProtectedDestroyError() {
        long vmId = createVm(VmStatus.DELETING);
        markPendingDeletion(vmId, "FORCE", Instant.now().minus(Duration.ofMinutes(1)));
        // Guest present but stopped (shutdown skipped). The pipeline clears the
        // always-on flag, yet PVE still refuses the destroy as protected — the
        // out-of-band re-set race the park branch exists for.
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("""
                                {"data":[{"vmid":%d,"type":"qemu","status":"stopped",
                                          "node":"%s","name":"test","tags":"pickle"}]}
                                """.formatted(proxmoxVmid, NODE_NAME))));
        stubConfigPut(proxmoxVmid);
        wm.server().stubFor(WireMock.delete(urlPathEqualTo(qemuBasePath()))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":null,\"message\":\"VM is protected - unable to remove\"}")));

        deleteVmJob.deleteVm(vmId);

        // immediate NEEDS_ADMIN park (never the backoff retry path)
        assertThat(taskState(vmId)).containsExactly("NEEDS_ADMIN", 1);
        assertThat(statusOf(vmId)).isEqualTo("DELETING");
        assertThat(statusDetailOf(vmId)).contains("보호");
    }

    @Test
    void deletePipelineParksWhenDeletionProtectionStillOnAtDestroyTime() throws Exception {
        // Reachable via an ADMIN-notice-window re-enable (or a skipped gate):
        // the destroy-time logical gate parks BEFORE any teardown or PVE call.
        long vmId = createVm(VmStatus.DELETING);
        markPendingDeletion(vmId, "ADMIN", Instant.now().minus(Duration.ofMinutes(1)));
        setSetting(vmId, "deletion_protection", "true");

        deleteVmJob.deleteVm(vmId);

        assertThat(taskState(vmId)).containsExactly("NEEDS_ADMIN", 1);
        assertThat(statusOf(vmId)).isEqualTo("DELETING");
        assertThat(statusDetailOf(vmId)).contains("삭제 보호가 켜진 상태");
        // no Proxmox interaction at all: PVE protection stays armed, no destroy
        assertThat(wm.server().getAllServeEvents()).isEmpty();

        // Recovery path (the park detail's advertised escape): override
        // force-delete persists the setting off AND resumes the parked task —
        // claimTask never claims NEEDS_ADMIN, so without the resume the
        // enqueued run would silently no-op and the VM would wedge.
        String vmName = jdbcTemplate.queryForObject("select name from vms where id = ?",
                String.class, vmId);
        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/force-delete")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmName", vmName, "overrideProtection", true))))
                .andExpect(status().isAccepted());
        // attempts cleared with the resume — a retry-exhaustion park (attempts
        // = MAX) must get a fresh retry budget, not re-park on first failure
        assertThat(taskState(vmId)).containsExactly("PENDING", 0);
        assertThat(settingValue(vmId, "deletion_protection")).isEqualTo("false");

        // the resumed run now destroys clean (guest stopped → shutdown skipped)
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("""
                                {"data":[{"vmid":%d,"type":"qemu","status":"stopped",
                                          "node":"%s","name":"test","tags":"pickle"}]}
                                """.formatted(proxmoxVmid, NODE_NAME))));
        stubConfigPut(proxmoxVmid);
        wm.server().stubFor(WireMock.delete(urlPathEqualTo(qemuBasePath()))
                .willReturn(okFixture("70-delete")));
        wm.server().stubFor(get(urlPathEqualTo(taskStatusPath(DELETE_UPID)))
                .willReturn(okFixture("70-delete-status")));
        deleteVmJob.deleteVm(vmId);
        assertThat(statusOf(vmId)).isEqualTo("DELETED");
    }

    @Test
    void deleteClearFailureRetriesInsteadOfProtectionPark() {
        long vmId = createVm(VmStatus.DELETING);
        markPendingDeletion(vmId, "SELF", Instant.now().minus(Duration.ofMinutes(1)));
        wm.server().stubFor(get(urlPathEqualTo("/api2/json/cluster/resources"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("""
                                {"data":[{"vmid":%d,"type":"qemu","status":"stopped",
                                          "node":"%s","name":"test","tags":"pickle"}]}
                                """.formatted(proxmoxVmid, NODE_NAME))));
        // The protection-clear config PUT fails — deliberately with "protect"
        // in the PVE message: the never-retried park is scoped to the destroy
        // call only, so even this must take the backoff retry path.
        wm.server().stubFor(WireMock.put(urlPathEqualTo(qemuPath("config")))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":null,\"message\":\"protection option boom\"}")));

        deleteVmJob.deleteVm(vmId);

        assertThat(taskState(vmId)).containsExactly("RETRYING", 1);
        wm.server().verify(0, deleteRequestedFor(urlPathEqualTo(qemuBasePath())));
    }

    @Test
    void deletionProtectionRefusesAllPathsAndOverridePersistsSettingOff() throws Exception {
        long vmId = createVm(VmStatus.RUNNING);
        String vmName = jdbcTemplate.queryForObject("select name from vms where id = ?",
                String.class, vmId);
        setSetting(vmId, "deletion_protection", "true");

        // self-delete, admin schedule-delete, and force-delete are all refused
        mockMvc.perform(delete("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_DELETION_PROTECTED"));
        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/schedule-delete")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "scheduledFor", Instant.now().plus(Duration.ofDays(30)).toString(),
                                "reason", "정리"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_DELETION_PROTECTED"));
        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/force-delete")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("confirmName", vmName))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_DELETION_PROTECTED"));

        // Override force-delete persists deletion_protection=false in the same
        // tx (the destroy pipeline re-checks it — incl. a sweeper-recovered run
        // after a lost enqueue) and never touches PVE in the request thread.
        mockMvc.perform(post("/api/v1/admin/vms/" + vmId + "/force-delete")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "confirmName", vmName, "overrideProtection", true))))
                .andExpect(status().isAccepted());
        assertThat(statusOf(vmId)).isEqualTo("DELETING");
        assertThat(settingValue(vmId, "deletion_protection")).isEqualTo("false");
        wm.server().verify(0, WireMock.putRequestedFor(
                urlPathEqualTo("/api2/json/nodes/" + NODE_NAME + "/qemu/" + proxmoxVmid
                        + "/config")));
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action='vm.force_delete' and target_id=? and detail::text like '%overrodeProtection%'
                """, Long.class, vmId)).isEqualTo(1L);
    }

    @Test
    void deletionProtectionToggleIsPveSilentAndAllowedWithoutVmid() throws Exception {
        // The setting is a pure logical gate: toggling never calls Proxmox
        // (the hypervisor flag is always-on platform state) …
        long vmId = createVm(VmStatus.RUNNING);
        patchSetting(ownerToken, vmId, "deletion_protection", true).andExpect(status().isOk());
        assertThat(settingValue(vmId, "deletion_protection")).isEqualTo("true");
        patchSetting(ownerToken, vmId, "deletion_protection", false).andExpect(status().isOk());
        assertThat(settingValue(vmId, "deletion_protection")).isEqualTo("false");
        assertThat(wm.server().getAllServeEvents()).isEmpty();

        // … so a vmid-less (still-provisioning) VM can toggle it too
        long creatingVmId = createVm(VmStatus.CREATING);
        jdbcTemplate.update("update vms set proxmox_vmid = null where id = ?", creatingVmId);
        patchSetting(ownerToken, creatingVmId, "deletion_protection", true)
                .andExpect(status().isOk());
        assertThat(settingValue(creatingVmId, "deletion_protection")).isEqualTo("true");
    }

    @Test
    void stopProtectionGatesMemberPowerOps() throws Exception {
        long vmId = createVm(VmStatus.RUNNING);
        setSetting(vmId, "stop_protection", "true");
        // MEMBER is blocked from shutdown while stop protection is on
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/shutdown")
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_STOP_PROTECTED"));
        // OWNER (>= EDITOR) is allowed
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/shutdown")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
    }

    private void stubConfigPut(int vmid) {
        wm.server().stubFor(WireMock.put(urlPathEqualTo(
                        "/api2/json/nodes/" + NODE_NAME + "/qemu/" + vmid + "/config"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json;charset=UTF-8")
                        .withBody("{\"data\":null}")));
    }

    private void setSetting(long vmId, String key, String jsonValue) {
        jdbcTemplate.update("insert into vm_settings (vm_id, key, value) values (?, ?, ?::jsonb)",
                vmId, key, jsonValue);
    }

    private String settingValue(long vmId, String key) {
        return jdbcTemplate.query("select value::text from vm_settings where vm_id = ? and key = ?",
                rs -> rs.next() ? rs.getString(1) : null, vmId, key);
    }

    private org.springframework.test.web.servlet.ResultActions patchSetting(String token, long vmId,
            String key, Object value) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/v1/vms/" + vmId + "/settings")
                .header("Authorization", "Bearer " + token)
                .header(ReauthTestSupport.HEADER, reauth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("settings", Map.of(key, value)))));
    }

    private long ensureWireMockNode() {
        Long existing = jdbcTemplate.query("select id from nodes where name = ?",
                rs -> rs.next() ? rs.getLong(1) : null, NODE_NAME);
        if (existing != null) {
            jdbcTemplate.update("update nodes set api_host = ? where id = ?", wm.apiHost(), existing);
            return existing;
        }
        return jdbcTemplate.queryForObject("""
                insert into nodes (name, api_host, cpu_threads, memory_mb, vm_bridge, storage)
                values (?, ?, 8, 16384, 'vmbr2', 'local-lvm') returning id
                """, Long.class, NODE_NAME, wm.apiHost());
    }

    /** Self-delete, settings patch and member management are sudo-mode gated. */
    private String reauth(String token) {
        return ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService, token);
    }

    private long createVm(VmStatus status) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, template_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '삭제 테스트', ?, 1, 1024, 10)
                returning id
                """, Long.class, groupId, orgId, owner.getId(), templateId);
        String hostname = "vmdel-" + UUID.randomUUID().toString().substring(0, 12);
        proxmoxVmid = VMID_SEQ.incrementAndGet();
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 template_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, ?::vm_status)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                templateId, proxmoxVmid, status.name());
    }

    private long createTeam(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", "삭제 테스트 " + slug, "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void addMember(long groupId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "role", role))))
                .andExpect(status().isCreated());
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
