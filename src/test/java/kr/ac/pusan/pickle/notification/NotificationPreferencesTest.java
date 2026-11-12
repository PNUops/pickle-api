package kr.ac.pusan.pickle.notification;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Self-only usage-mail preferences do not change any other notification policy. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class NotificationPreferencesTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @Autowired private org.springframework.context.ApplicationContext context;
    @Autowired private kr.ac.pusan.pickle.llm.alert.LlmUsageAlertService alerts;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private kr.ac.pusan.pickle.mail.MockMailSender mail;
    @Autowired private NotificationDispatchJob dispatcher;
    @Autowired private NotificationService notifications;
    private String first;
    private String second;

    @BeforeEach
    void activeAccounts() {
        first = token();
        second = token();
    }

    @Test
    void absentPersonalPreferenceDefaultsToUsageMailEnabled() throws Exception {
        mvc.perform(get("/api/v1/me/notification-preferences").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usageMailEnabled").value(true));
    }

    @Test
    void aPersonalChangePersistsWithoutChangingAnotherAccount() throws Exception {
        mvc.perform(put("/api/v1/me/notification-preferences").header("Authorization", "Bearer " + first)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"usageMailEnabled\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usageMailEnabled").value(false));
        mvc.perform(get("/api/v1/me/notification-preferences").header("Authorization", "Bearer " + first))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usageMailEnabled").value(false));
        mvc.perform(get("/api/v1/me/notification-preferences").header("Authorization", "Bearer " + second))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usageMailEnabled").value(true));
    }

    @Test
    void anOmittedPreferenceCannotSilentlyBecomeFalse() throws Exception {
        mvc.perform(put("/api/v1/me/notification-preferences").header("Authorization", "Bearer " + first)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void unauthenticatedPreferenceReadsRemainClosed() throws Exception {
        mvc.perform(get("/api/v1/me/notification-preferences")).andExpect(status().isUnauthorized());
    }

    @Test
    void operatingEvaluationIsDisabledIndependentlyOfPersonalDefaultOn() {
        assertThat(context.getBeansOfType(kr.ac.pusan.pickle.llm.alert.LlmUsageAlertJob.class)).isEmpty();
        long before = jdbc.queryForObject("select count(*) from llm_key_usage_alert_state", Long.class);
        assertThat(alerts.evaluate()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from llm_key_usage_alert_state", Long.class)).isEqualTo(before);
    }

    @Test
    void disabledUsageMailDoesNotChangeLegacyFrozenRecipientDelivery() {
        jdbc.update("update notifications set status='SENT',sent_at=now() where status='PENDING'"); mail.clear();
        User recipient = new User("frozen-" + UUID.randomUUID() + "@example.com", "{test-no-login}", "Frozen fixture");
        recipient.setStatus(UserStatus.ACTIVE); recipient = users.save(recipient);
        notifications.publish(recipient.getId(), NotificationEvent.ANNOUNCEMENT,
                java.util.Map.of("title", "Legacy frozen notice", "message", "Existing snapshot"), "frozen-" + UUID.randomUUID());
        long usageId = jdbc.queryForObject("""
                insert into notifications(user_id,event,title,body,link_path,importance,payload,status,recipient_email)
                values(?,'llm.usage.threshold','Prior usage observation','Factual queued observation',
                    '/console/notifications','NORMAL',?::jsonb,'PENDING',?) returning id
                """, Long.class, recipient.getId(), "{\"keyId\":\"" + UUID.randomUUID() + "\",\"axis\":\"TOKEN\"}", recipient.getEmail());
        jdbc.update("update users set status='DISABLED' where id=?", recipient.getId());
        dispatcher.dispatch();
        assertThat(jdbc.queryForObject("select status::text from notifications where id=?", String.class, usageId)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("select skip_reason from notifications where id=?", String.class, usageId)).isEqualTo("USAGE_ALERTS_DISABLED");
        assertThat(mail.getMessages()).hasSize(1);
        assertThat(mail.getMessages().getFirst().subject()).contains("Legacy frozen notice");
    }

    private String token() {
        User user = new User("usage-mail-" + UUID.randomUUID() + "@example.com", "{test-no-login}", "Preference fixture");
        user.setStatus(UserStatus.ACTIVE);
        return jwt.createAccessToken(users.save(user));
    }
}
