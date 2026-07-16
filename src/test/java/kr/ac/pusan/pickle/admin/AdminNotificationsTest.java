package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SYS_ADMIN delivery log per contract: recipient/event/status filters and the
 * AdminNotificationView shape, plus the resend CAS matrix — FAILED → 202 with
 * status back to PENDING due immediately (attempts kept counting), everything
 * else 409 {@code NOTIFICATION_NOT_RESENDABLE}, unknown 404, audited.
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminNotificationsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String sysAdminToken;
    private String orgAdminToken;
    private long recipientId;
    private String recipientEmail;

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail("admin@pickle.local").orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail("orgadmin@pickle.local").orElseThrow());
        recipientEmail = "adn." + UUID.randomUUID().toString().substring(0, 8) + "@pusan.ac.kr";
        User recipient = new User(recipientEmail, "{test-no-login}", "발송로그테스트");
        recipient.setRole(UserRole.USER);
        recipient.setStatus(UserStatus.ACTIVE);
        recipient.setEmailVerifiedAt(Instant.now());
        recipientId = userRepository.save(recipient).getId();
    }

    @Test
    void listsTheDeliveryLogWithFiltersAndShape() throws Exception {
        long failed = insertNotification(recipientId, "vm.create.done", "FAILED",
                "SMTP 오류: 연결 실패");
        long sent = insertNotification(recipientId, "vm.expiry.d7", "SENT", null);

        mockMvc.perform(get("/api/v1/admin/notifications")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/notifications?email=" + recipientEmail)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath(byId(failed)).exists())
                .andExpect(jsonPath(byId(sent)).exists())
                .andExpect(jsonPath(byId(failed) + ".userId").value((int) recipientId))
                .andExpect(jsonPath(byId(failed) + ".userEmail").value(recipientEmail))
                .andExpect(jsonPath(byId(failed) + ".channel").value("EMAIL"))
                .andExpect(jsonPath(byId(failed) + ".status").value("FAILED"))
                .andExpect(jsonPath(byId(failed) + ".attempts").value(3))
                .andExpect(jsonPath(byId(failed) + ".lastError").value("SMTP 오류: 연결 실패"))
                .andExpect(jsonPath(byId(failed) + ".title").isNotEmpty())
                .andExpect(jsonPath(byId(failed) + ".importance").value("NORMAL"))
                .andExpect(jsonPath(byId(sent) + ".sentAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/admin/notifications?email=%s&status=FAILED"
                        .formatted(recipientEmail))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(failed)).exists())
                .andExpect(jsonPath(byId(sent)).doesNotExist());

        mockMvc.perform(get("/api/v1/admin/notifications?email=%s&event=vm.expiry.d7"
                        .formatted(recipientEmail))
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(sent)).exists())
                .andExpect(jsonPath(byId(failed)).doesNotExist());
    }

    @Test
    void resendIsCasFromFailedOnlyAndAudited() throws Exception {
        long failed = insertNotification(recipientId, "vm.create.done", "FAILED", "SMTP 오류");
        long sent = insertNotification(recipientId, "vm.create.done", "SENT", null);
        // due far in the future so the immediate re-queue is observable
        jdbcTemplate.update(
                "update notifications set next_attempt_at = now() + interval '1 day' where id = ?",
                failed);

        mockMvc.perform(post("/api/v1/admin/notifications/{id}/resend", failed)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").isNotEmpty());

        Map<String, Object> row = jdbcTemplate.queryForMap("""
                select status::text as status, attempts, next_attempt_at <= now() as due_now
                  from notifications where id = ?
                """, failed);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(3); // NOT reset
        assertThat(row.get("due_now")).isEqualTo(true);
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from audit_logs
                 where action = 'notification.resend' and target_id = ?
                """, Long.class, failed)).isEqualTo(1);

        // now PENDING → a second resend answers 409 (CAS idempotence)
        mockMvc.perform(post("/api/v1/admin/notifications/{id}/resend", failed)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_RESENDABLE"));

        mockMvc.perform(post("/api/v1/admin/notifications/{id}/resend", sent)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_RESENDABLE"));

        mockMvc.perform(post("/api/v1/admin/notifications/999999/resend")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/admin/notifications/{id}/resend", failed)
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden());
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private static String byId(long id) {
        return "$.content[?(@.id == %d)]".formatted(id);
    }

    private long insertNotification(long userId, String event, String status, String lastError) {
        return jdbcTemplate.queryForObject("""
                insert into notifications (user_id, event, title, body, importance, status,
                                           attempts, last_error, sent_at)
                values (?, ?, '발송 로그 테스트', '본문', 'NORMAL', ?::notification_status, 3, ?,
                        case when ? = 'SENT' then now() end)
                returning id
                """, Long.class, userId, event, status, lastError, status);
    }
}
