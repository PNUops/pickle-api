package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.AccessGrantFixtures;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
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
 * Expiry API surface: {@code GET /admin/vms} expiringInDays/expired filters
 * (+ the VmSummary endDate/expiryStoppedAt fields), the {@code VM_EXPIRED}
 * start guard, and {@code PATCH /admin/vms/{vmId}/period} (validation 422,
 * deletion-bound 409, ORG_ADMIN 404 mask, marker clearing that re-enables
 * start). The JobRunr server is off so accepted power actions stay observable.
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminVmPeriodTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private LocalDate today;
    private long orgId;
    private long groupId;
    private String sysAdminToken;
    private String memberToken;
    private long memberId;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(KST);
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        String slug = "avp-" + UUID.randomUUID().toString().substring(0, 8);
        groupId = jdbcTemplate.queryForObject(
                "insert into groups (kind, name, slug) values ('TEAM', ?, ?) returning id",
                Long.class, slug, slug);
        User member = ensureUser("avp.member." + slug + "@pusan.ac.kr", UserRole.USER, null);
        memberId = member.getId();
        jdbcTemplate.update("""
                insert into group_members (group_id, user_id, role)
                values (?, ?, 'MEMBER'::group_member_role)
                """, groupId, memberId);
        memberToken = jwtService.createAccessToken(member);
    }

    @Test
    void adminVmListFiltersByExpiryAndExposesTheNewFields() throws Exception {
        long expiring = createVm(orgId, groupId, "RUNNING", today.plusDays(5));
        long expiringLater = createVm(orgId, groupId, "RUNNING", today.plusDays(20));
        long expired = createVm(orgId, groupId, "STOPPED", today.minusDays(1));
        long deletedExpired = createVm(orgId, groupId, "DELETED", today.minusDays(3));
        long undated = createVm(orgId, groupId, "RUNNING", null);

        mockMvc.perform(get("/api/v1/admin/vms?expiringInDays=7&groupId=" + groupId)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(expiring)).exists())
                .andExpect(jsonPath(byId(expiringLater)).doesNotExist()) // beyond horizon
                .andExpect(jsonPath(byId(expired)).doesNotExist()) // already expired
                .andExpect(jsonPath(byId(undated)).doesNotExist())
                .andExpect(jsonPath(byId(expiring) + ".endDate")
                        .value(today.plusDays(5).toString()));

        mockMvc.perform(get("/api/v1/admin/vms?expired=true&groupId=" + groupId)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(expired)).exists())
                .andExpect(jsonPath(byId(deletedExpired)).doesNotExist()) // DELETED excluded
                .andExpect(jsonPath(byId(expiring)).doesNotExist())
                .andExpect(jsonPath(byId(undated)).doesNotExist());

        // both filters AND to an empty page by design
        mockMvc.perform(get("/api/v1/admin/vms?expiringInDays=7&expired=true&groupId=" + groupId)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());

        mockMvc.perform(get("/api/v1/admin/vms?expiringInDays=0")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void expiredVmRefusesStartUntilThePeriodIsExtended() throws Exception {
        long vmId = createVm(orgId, groupId, "STOPPED", today.minusDays(2));
        // The VM is inserted straight into the database, so its access list is
        // empty and nobody could power it at all. Naming the member on it is
        // what puts the expiry guard — not the access check — under test.
        AccessGrantFixtures.grantVmToUser(jdbcTemplate, vmId, memberId, "MEMBER");
        jdbcTemplate.update("update vms set expiry_stopped_at = now(),"
                + " last_expiry_notice_stage = 1 where id = ?", vmId);

        mockMvc.perform(post("/api/v1/vms/{id}/start", vmId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_EXPIRED"));

        // the admin extends the period → markers clear in the same update
        mockMvc.perform(patch("/api/v1/admin/vms/{id}/period", vmId)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"%s\"}".formatted(today.plusDays(30))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(vmId))
                .andExpect(jsonPath("$.endDate").value(today.plusDays(30).toString()))
                .andExpect(jsonPath("$.expiryStoppedAt").isEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        assertThat(jdbcTemplate.queryForMap(
                "select expiry_stopped_at, last_expiry_notice_stage from vms where id = ?", vmId))
                .containsEntry("expiry_stopped_at", null)
                .containsEntry("last_expiry_notice_stage", null);
        // recorded in the permanent history + the audit trail
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from vm_events where vm_id = ? and type = 'PERIOD_UPDATE'
                """, Long.class, vmId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action = 'vm.period_update' and target_id = ?
                """, Long.class, vmId)).isEqualTo(1);

        // guard lifted → start is accepted again
        mockMvc.perform(post("/api/v1/vms/{id}/start", vmId)
                        .header("Authorization", "Bearer " + memberToken))
                .andExpect(status().isAccepted());
    }

    @Test
    void periodPatchValidatesDatesAndDeletionState() throws Exception {
        long vmId = createVm(orgId, groupId, "RUNNING", today.plusDays(10));
        jdbcTemplate.update("update vms set start_date = ? where id = ?", today.minusDays(30), vmId);

        // endDate in the past → 422 with the field error
        mockMvc.perform(patch("/api/v1/admin/vms/{id}/period", vmId)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"%s\"}".formatted(today.minusDays(1))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("endDate"));

        // endDate before startDate → 422
        mockMvc.perform(patch("/api/v1/admin/vms/{id}/period", vmId)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"%s\", \"startDate\": \"%s\"}".formatted(
                                today.plusDays(5), today.plusDays(10))))
                .andExpect(status().isUnprocessableContent());

        // deletion-bound VM → 409 VM_INVALID_STATE
        long scheduled = createVm(orgId, groupId, "RUNNING", today.plusDays(10));
        jdbcTemplate.update("update vms set delete_scheduled_for = now() where id = ?", scheduled);
        mockMvc.perform(patch("/api/v1/admin/vms/{id}/period", scheduled)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"%s\"}".formatted(today.plusDays(30))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));

        long deleting = createVm(orgId, groupId, "DELETING", today.plusDays(10));
        mockMvc.perform(patch("/api/v1/admin/vms/{id}/period", deleting)
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\": \"%s\"}".formatted(today.plusDays(30))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
    }

    @Test
    void orgAdminIsMaskedFromOtherOrgsVmsAndUsersAreForbidden() throws Exception {
        Org otherOrg = orgRepository.findBySlug("avp-other").orElseGet(() ->
                orgRepository.save(new Org("기간 테스트 타기관", "avp-other", null)));
        User otherOrgAdmin = ensureUser("avp.other.admin@pusan.ac.kr", UserRole.ORG_ADMIN,
                otherOrg.getId());
        String otherOrgAdminToken = jwtService.createAccessToken(otherOrgAdmin);
        long vmId = createVm(orgId, groupId, "RUNNING", today.plusDays(10));
        String body = "{\"endDate\": \"%s\"}".formatted(today.plusDays(30));

        mockMvc.perform(patch("/api/v1/admin/vms/{id}/period", vmId)
                        .header("Authorization", "Bearer " + otherOrgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(patch("/api/v1/admin/vms/999999/period")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/admin/vms/{id}/period", vmId)
                        .header("Authorization", "Bearer " + memberToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static String byId(long vmId) {
        return "$.content[?(@.id == %d)]".formatted(vmId);
    }

    private User ensureUser(String email, UserRole role, Long userOrgId) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", "기간테스트");
            user.setRole(role);
            user.setOrgId(userOrgId);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    private long createVm(long vmOrgId, long vmGroupId, String status, LocalDate endDate) {
        long imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        long requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        long nodeId = jdbcTemplate.queryForObject("select id from nodes where name = 'pve1'", Long.class);
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, image_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '기간 테스트', ?, 2, 2048, 10)
                returning id
                """, Long.class, vmGroupId, vmOrgId, requesterId, imageId);
        String hostname = "avp-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, status, end_date)
                values (?, ?, ?, ?, ?, ?, ?, 2, 2048, 10, ?::vm_status, ?)
                returning id
                """, Long.class, nodeId, vmGroupId, vmOrgId, requestId, hostname, hostname,
                imageId, status, endDate);
    }
}
