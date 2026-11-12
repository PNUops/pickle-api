package kr.ac.pusan.pickle.llm.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.config.OpenRouterProperties;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterClient;
import kr.ac.pusan.pickle.mail.MockMailSender;
import kr.ac.pusan.pickle.notification.NotificationDispatchJob;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationPreferencesService;
import kr.ac.pusan.pickle.notification.NotificationService;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.SoftAssertions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real local rows, current ordinary grants, exact provider facts and mocked mail transport. */
@SpringBootTest(properties = "pickle.llm.usage-alerts.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, LlmUsageAlertIntegrationTest.TestClock.class})
class LlmUsageAlertIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private LlmUsageAlertService alerts;
    @Autowired private LlmUsageAlertState state;
    @Autowired private LlmApiKeyRepository keys;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MutableClock clock;
    @Autowired private NotificationService notifications;
    @Autowired private NotificationDispatchJob dispatcher;
    @Autowired private NotificationPreferencesService preferences;
    @Autowired private MockMailSender mail;
    @Autowired private PlatformTransactionManager manager;
    @MockitoBean private OpenRouterClient inactiveSpringClient;
    @MockitoSpyBean private LlmUsageAlertMailGuard mailGuard;
    private User owner;
    private String bearer;
    private long workspace;
    private long org;

    @BeforeEach
    void isolatedOwnerAndWorkspace() {
        clock.value = Instant.now();
        mail.clear();
        jdbc.update("update notifications set status='SENT',sent_at=now() where status='PENDING'");
        owner = new User("usage-alert-" + UUID.randomUUID() + "@example.com", "{test-no-login}", "Alert fixture");
        owner.setStatus(UserStatus.ACTIVE); owner = users.save(owner); bearer = jwt.createAccessToken(owner);
        org = SeedFixtures.seedOrgId(jdbc);
        workspace = jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT',?) returning id",
                Long.class, "Alert workspace " + UUID.randomUUID());
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, owner.getId());
    }

    @Test
    void eightyIsConsoleOnlyThenHundredMailsOnceOnAStableBasis() {
        LlmApiKey key = key(100, "0"); event(key, "TOKEN", null, 80);
        assertThat(alerts.evaluateKey(key.getId())).isEqualTo(1);
        assertThat(mailStatus(key)).isEqualTo("SKIPPED"); dispatcher.dispatch(); assertThat(mail.getMessages()).isEmpty();
        event(key, "TOKEN", null, 20);
        assertThat(alerts.evaluateKey(key.getId())).isEqualTo(1);
        assertThat(alerts.evaluateKey(key.getId())).isZero();
        dispatcher.dispatch(); assertThat(mail.getMessages()).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=? and event=?", Long.class,
                owner.getId(), LlmUsageAlertAccess.EVENT)).isEqualTo(2);
        assertThat(mail.getMessages().getFirst().textBody())
                .contains("Alert fixture의 일일 토큰 한도를 모두 사용했어요.", "/console/llm-keys/" + key.getPublicId());
    }

    @Test
    void jumpingStraightToHundredDoesNotLaterPublishALowerStage() {
        LlmApiKey key = key(100, "0"); event(key, "TOKEN", null, 100);
        assertThat(alerts.evaluateKey(key.getId())).isEqualTo(1);
        jdbc.update("update llm_usage_events set input_tokens=80 where key_id=?", key.getId());
        assertThat(alerts.evaluateKey(key.getId())).isZero();
        assertThat(jdbc.queryForObject("select highest_stage from llm_key_usage_alert_state where key_id=? and axis='TOKEN'",
                Integer.class, key.getId())).isEqualTo(100);
    }

    @Test
    void aChangedLimitBasisIsReevaluatedWithoutRepeatingEveryPoll() {
        LlmApiKey key = key(100, "0"); event(key, "TOKEN", null, 80);
        assertThat(alerts.evaluateKey(key.getId())).isEqualTo(1);
        jdbc.update("update llm_api_keys set daily_tokens=80 where id=?", key.getId());
        assertThat(alerts.evaluateKey(key.getId())).isEqualTo(1);
        assertThat(alerts.evaluateKey(key.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=? and event=?", Long.class,
                owner.getId(), LlmUsageAlertAccess.EVENT)).isEqualTo(2);
    }

    @Test
    void requestTimeAxisWinsAfterModelReclassificationAndLegacyNullStillFallsBack() {
        LlmApiKey key = key(100, "0"); String name = "alert-axis-" + UUID.randomUUID();
        jdbc.update("insert into llm_models(public_name,upstream_ref,upstream_model,budget_axis) values(?,'fixture','fixture','TOKEN')", name);
        event(key, "TOKEN", name, 60);
        jdbc.update("update llm_models set budget_axis='CREDIT' where public_name=?", name);
        event(key, "CREDIT", name, 1000);
        String legacy = "alert-legacy-" + UUID.randomUUID();
        jdbc.update("insert into llm_models(public_name,upstream_ref,upstream_model,budget_axis) values(?,'fixture','fixture','TOKEN')", legacy);
        event(key, null, legacy, 20);
        assertThat(alerts.evaluateKey(key.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select body from notifications where user_id=? and event=?", String.class,
                owner.getId(), LlmUsageAlertAccess.EVENT))
                .isEqualTo("Alert fixture의 일일 토큰 한도를 80% 이상 사용했어요.");
    }

    @Test
    void aWorkspaceOwnerWithoutAnActualKeyGrantIsNotAUsageRecipient() throws Exception {
        LlmApiKey key = key(100, "0"); revoke(key); event(key, "TOKEN", null, 100);
        assertThat(alerts.evaluateKey(key.getId())).isZero();
        assertThat(jdbc.queryForObject("select last_recipient_count from llm_key_usage_alert_state where key_id=? and axis='TOKEN'",
                Integer.class, key.getId())).isZero();
        mvc.perform(get("/api/v1/llm-keys/" + key.getPublicId() + "/usage").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isForbidden());
    }

    @Test
    void lostReadAuthorityHidesAllUsageFieldsCountsAndReadReceiptsButNotLegacyRows() throws Exception {
        LlmApiKey key = key(100, "0"); event(key, "TOKEN", null, 100); alerts.evaluateKey(key.getId());
        UUID notification = notification(key); revoke(key);
        notifications.publish(owner.getId(), NotificationEvent.ANNOUNCEMENT,
                Map.of("title", "Legacy notice", "message", "Existing frozen behavior"), "legacy-" + UUID.randomUUID());
        JsonNode inbox = read("/api/v1/notifications");
        assertThat(inbox.get("totalElements").asLong()).isEqualTo(1);
        assertThat(inbox.get("content").get(0).get("event").asText()).isEqualTo("announcement");
        assertThat(read("/api/v1/notifications/unread-count").get("unreadCount").asLong()).isEqualTo(1);
        mvc.perform(post("/api/v1/notifications/" + notification + "/read").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isNotFound());
        JsonNode all = mapper.readTree(mvc.perform(post("/api/v1/notifications/read-all")
                .header("Authorization", "Bearer " + bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(all.get("updatedCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select read_at is null from notifications where public_id=?", Boolean.class, notification)).isTrue();
        dispatcher.dispatch();
        assertThat(mailStatus(key)).isEqualTo("SKIPPED");
        assertThat(mail.getMessages()).hasSize(1);
        assertThat(mail.getMessages().getFirst().subject()).contains("Legacy notice");
    }

    @Test
    void preferenceOffKeepsConsoleAndDoesNotDisableAnotherRecipientOrLegacyMail() throws Exception {
        LlmApiKey key = key(100, "0");
        User second = new User("usage-second-" + UUID.randomUUID() + "@example.com", "{test-no-login}", "Second fixture");
        second.setStatus(UserStatus.ACTIVE); second = users.save(second);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspace, second.getId());
        jdbc.update("insert into resource_access_grants(resource_type,resource_id,grantee_type,user_id,role) values('LLM_API_KEY',?,'USER',?,'OWNER')", key.getId(), second.getId());
        mvc.perform(put("/api/v1/me/notification-preferences").header("Authorization", "Bearer " + bearer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"usageMailEnabled\":false}"))
                .andExpect(status().isOk());
        event(key, "TOKEN", null, 100); assertThat(alerts.evaluateKey(key.getId())).isEqualTo(2);
        assertThat(read("/api/v1/notifications").get("totalElements").asLong()).isEqualTo(1);
        dispatcher.dispatch(); assertThat(mail.getMessages()).hasSize(1);
        assertThat(mail.lastMessageTo(owner.getEmail())).isNull();
        assertThat(mail.lastMessageTo(second.getEmail())).isNotNull();
        notifications.publish(owner.getId(), NotificationEvent.ANNOUNCEMENT,
                Map.of("title", "Unchanged mail", "message", "Legacy policy"), "other-" + UUID.randomUUID());
        dispatcher.dispatch(); assertThat(mail.getMessages()).hasSize(2);
    }

    @Test
    void theLastPreSmtpAuthorityCheckSkipsAndPermanentlyFinishesTheClaim() {
        LlmApiKey key = key(100, "0"); event(key, "TOKEN", null, 100); alerts.evaluateKey(key.getId());
        long id = jdbc.queryForObject("select id from notifications where public_id=?", Long.class, notification(key));
        doAnswer(invocation -> { revoke(key); return invocation.callRealMethod(); })
                .when(mailGuard).skipClaimed(eq(id), any(UUID.class));
        dispatcher.dispatch(); assertThat(mail.getMessages()).isEmpty(); assertThat(mailStatus(key)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("select state from mail_deliveries where notification_id=?", String.class, id)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("select a.outcome from mail_delivery_attempts a join mail_deliveries d on d.id=a.delivery_id where d.notification_id=?",
                String.class, id)).isEqualTo("SKIPPED");
    }

    @Test
    void theLastPreSmtpPreferenceCheckSkipsOnlyThisUsageClaim() {
        LlmApiKey key = key(100, "0"); event(key, "TOKEN", null, 100); alerts.evaluateKey(key.getId());
        long id = jdbc.queryForObject("select id from notifications where public_id=?", Long.class, notification(key));
        doAnswer(invocation -> { preferences.update(owner.getId(), false); return invocation.callRealMethod(); })
                .when(mailGuard).skipClaimed(eq(id), any(UUID.class));
        dispatcher.dispatch(); assertThat(mail.getMessages()).isEmpty(); assertThat(mailStatus(key)).isEqualTo("SKIPPED");
    }

    @Test
    void factualQueuedMailRemainsReadableAfterExpiryAndASuspension() throws Exception {
        LlmApiKey key = key(100, "0"); event(key, "TOKEN", null, 100); alerts.evaluateKey(key.getId());
        jdbc.update("update llm_api_keys set status='SUSPENDED',expires_at=? where id=?", Timestamp.from(clock.instant().minusSeconds(1)), key.getId());
        assertThat(read("/api/v1/notifications").get("totalElements").asLong()).isEqualTo(1);
        mvc.perform(post("/api/v1/notifications/" + notification(key) + "/read").header("Authorization", "Bearer " + bearer))
                .andExpect(status().isOk());
        dispatcher.dispatch(); assertThat(mail.getMessages()).hasSize(1); assertThat(mailStatus(key)).isEqualTo("SENT");
    }

    @Test
    void exactProviderWireReachesCurrentStorageAndUsesRemainingRatherThanLifetimeUsage() throws Exception {
        LlmApiKey key = key(0, "1");
        var server = new com.github.tomakehurst.wiremock.WireMockServer(
                com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
        try {
            server.start(); OpenRouterClient client = new OpenRouterClient(new OpenRouterProperties(server.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(5)), clock);
            provider(server, client, key, "0.20000000000000001", "daily", true);
            assertThat(alerts.evaluateKey(key.getId())).isZero();
            provider(server, client, key, "0.2", "daily", true);
            assertThat(alerts.evaluateKey(key.getId())).isEqualTo(1); assertThat(mailStatus(key)).isEqualTo("SKIPPED");
            provider(server, client, key, "0", "daily", true);
            assertThat(alerts.evaluateKey(key.getId())).isEqualTo(1); dispatcher.dispatch();
            assertThat(mail.getMessages()).hasSize(1);
            assertThat(mail.getMessages().getFirst().textBody())
                    .contains("Alert fixture의 일일 금액 한도를 모두 사용했어요.")
                    .doesNotContain("당시 한도", "관측 시각", "UTC");
        } finally { server.stop(); }
    }

    @Test
    void staleFailedAndUnsupportedCreditObservationsDeferInsteadOfZero() throws Exception {
        LlmApiKey key = key(0, "1");
        var server = new com.github.tomakehurst.wiremock.WireMockServer(
                com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
        try {
            server.start(); OpenRouterClient client = new OpenRouterClient(new OpenRouterProperties(server.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(5)), clock);
            provider(server, client, key, "0", "daily", false); assertThat(alerts.evaluateKey(key.getId())).isZero();
            provider(server, client, key, "0", "daily", true);
            jdbc.update("update openrouter_accounts set keys_error='VENDOR_UNAVAILABLE' where id=?", key.getOpenrouterAccountId());
            assertThat(alerts.evaluateKey(key.getId())).isZero();
            jdbc.update("update openrouter_accounts set keys_error=null where id=?", key.getOpenrouterAccountId());
            clock.value = clock.instant().plus(Duration.ofMinutes(31)); assertThat(alerts.evaluateKey(key.getId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=? and event=?", Long.class,
                    owner.getId(), LlmUsageAlertAccess.EVENT)).isZero();
        } finally { server.stop(); }
    }

    @Test
    void aProviderFetchSpanningTheUtcResetBoundaryIsDeferred() {
        LlmApiKey key = key(0, "1");
        Instant boundary = clock.instant().atZone(ZoneOffset.UTC).toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        clock.value = boundary.plusSeconds(1);
        Clock fetchClock = new Clock() {
            private int calls;
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return calls++ == 0 ? boundary.minusMillis(1) : boundary.plusMillis(1); }
        };
        var server = new com.github.tomakehurst.wiremock.WireMockServer(
                com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());
        try {
            server.start();
            OpenRouterClient client = new OpenRouterClient(new OpenRouterProperties(server.baseUrl(), Duration.ofSeconds(2), Duration.ofSeconds(5)), fetchClock);
            provider(server, client, key, "0", "daily", true);
            assertThat(alerts.evaluateKey(key.getId())).isZero();
        } finally { server.stop(); }
    }

    private void provider(com.github.tomakehurst.wiremock.WireMockServer server, OpenRouterClient client,
            LlmApiKey key, String remaining, String reset, boolean include) {
        server.resetAll();
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/keys/" + key.getOpenrouterKeyHash()))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{\"data\":{\"hash\":\"" + key.getOpenrouterKeyHash() + "\",\"disabled\":false,\"limit\":1,"
                                + "\"limit_reset\":\"" + reset + "\",\"limit_remaining\":" + remaining + ",\"include_byok_in_limit\":" + include + ",\"usage\":9000}}")));
        jdbc.update("update llm_api_keys set credit_limit_reset='DAILY' where id=?", key.getId());
        jdbc.update("update openrouter_accounts set keys_last_success_at=? where id=?", Timestamp.from(clock.instant()), key.getOpenrouterAccountId());
        LlmApiKey current = keys.findById(key.getId()).orElseThrow();
        var observation = client.getKey("synthetic-management", null, key.getOpenrouterKeyHash()).currentObservation();
        new TransactionTemplate(manager).executeWithoutResult(tx -> state.capture(current, observation));
    }

    @Test
    void malformedUsagePayloadIsHiddenFromCountsAndEveryReadResponse() throws Exception {
        LlmApiKey key = key(100, "0");
        var ids = malformed(key);
        notifications.publish(owner.getId(), NotificationEvent.ANNOUNCEMENT,
                Map.of("title", "Legacy without axis", "message", "Existing snapshot"), "axis-control-" + UUID.randomUUID());
        long total = read("/api/v1/notifications").get("totalElements").asLong();
        long unread = read("/api/v1/notifications/unread-count").get("unreadCount").asLong();
        JsonNode all = mapper.readTree(mvc.perform(post("/api/v1/notifications/read-all")
                .header("Authorization", "Bearer " + bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        java.util.List<Integer> reads = new java.util.ArrayList<>();
        for (UUID id : ids) { reads.add(mvc.perform(post("/api/v1/notifications/" + id + "/read")
                .header("Authorization", "Bearer " + bearer)).andReturn().getResponse().getStatus()); }
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(total).as("invalid usage rows never affect total").isEqualTo(1);
            softly.assertThat(unread).as("invalid usage rows never affect unread count").isEqualTo(1);
            softly.assertThat(all.get("updatedCount").asInt()).as("read-all excludes invalid usage rows").isEqualTo(1);
            softly.assertThat(reads).as("missing and unsupported axis read responses").containsExactly(404, 404);
        });
    }

    @Test
    void malformedUsagePayloadIsSkippedBeforeMockSmtp() {
        LlmApiKey key = key(100, "0"); malformed(key);
        dispatcher.dispatch();
        assertThat(mail.getMessages()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from notifications where user_id=? and event=? and status='SKIPPED'",
                Long.class, owner.getId(), LlmUsageAlertAccess.EVENT)).isEqualTo(2);
    }

    @Test
    void legacyPayloadWithoutAxisKeepsItsExistingReadAndDeliveryPolicy() throws Exception {
        notifications.publish(owner.getId(), NotificationEvent.ANNOUNCEMENT,
                Map.of("title", "Legacy axis control", "message", "No usage axis"), "legacy-axis-" + UUID.randomUUID());
        assertThat(read("/api/v1/notifications").get("totalElements").asLong()).isEqualTo(1);
        dispatcher.dispatch(); assertThat(mail.getMessages()).hasSize(1);
    }

    private java.util.List<UUID> malformed(LlmApiKey key) {
        var result = new java.util.ArrayList<UUID>();
        for (Map<String, Object> payload : java.util.List.of(Map.<String, Object>of("keyId", key.getPublicId()),
                Map.<String, Object>of("keyId", key.getPublicId(), "axis", "UNKNOWN"))) {
            result.add(jdbc.queryForObject("""
                    insert into notifications(user_id,event,title,body,link_path,importance,payload,status,recipient_email)
                    values(?,'llm.usage.threshold','Unverified fixture','Malformed snapshot',?,
                        'NORMAL',?::jsonb,'PENDING',?) returning public_id
                    """, UUID.class, owner.getId(), "/console/llm-keys/" + key.getPublicId(),
                    mapper.writeValueAsString(payload), owner.getEmail()));
        }
        return result;
    }

    private LlmApiKey key(long tokens, String credit) {
        long account = jdbc.queryForObject("insert into openrouter_accounts(org_id,name,created_by,keys_last_success_at) values(?,?,?,?) returning id",
                Long.class, org, "Alert account " + UUID.randomUUID(), owner.getId(), Timestamp.from(clock.instant()));
        long request = jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) "
                + "values('LLM_API_KEY',?,?,?,'Alert fixture','Alert fixture') returning id", Long.class, workspace, org, owner.getId());
        boolean paid = new BigDecimal(credit).signum() > 0;
        long id = jdbc.queryForObject("""
                insert into llm_api_keys(workspace_id,org_id,request_id,name,token_hash,token_prefix,status,
                    daily_tokens,credit_limit,openrouter_account_id,openrouter_key_hash,openrouter_key_enc,created_by)
                values(?,?,?,'Alert fixture',?,'fixture','ACTIVE',?,?,?,?,?,?) returning id
                """, Long.class, workspace, org, request, (UUID.randomUUID().toString()+UUID.randomUUID()).replace("-", ""),
                tokens, new BigDecimal(credit), paid ? account : null, paid ? "synthetic-" + UUID.randomUUID() : null,
                paid ? "synthetic-ciphertext" : null, owner.getId());
        jdbc.update("insert into resource_access_grants(resource_type,resource_id,grantee_type,user_id,role) values('LLM_API_KEY',?,'USER',?,'OWNER')", id, owner.getId());
        return keys.findById(id).orElseThrow();
    }

    private void event(LlmApiKey key, String axis, String model, int tokens) {
        jdbc.update("insert into llm_usage_events(event_id,key_id,public_model_name,budget_axis,status,input_tokens,output_tokens,estimated,latency_ms,requested_at,received_at) "
                + "values(?,?,?,?,'OK',?,0,false,1,?,?)", UUID.randomUUID().toString(), key.getId(), model, axis, tokens,
                Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
    }
    private void revoke(LlmApiKey key) { jdbc.update("delete from resource_access_grants where resource_type='LLM_API_KEY' and resource_id=? and user_id=?", key.getId(), owner.getId()); }
    private UUID notification(LlmApiKey key) { return jdbc.queryForObject("select public_id from notifications where user_id=? and event=? and payload->>'keyId'=? order by id desc limit 1", UUID.class, owner.getId(), LlmUsageAlertAccess.EVENT, key.getPublicId().toString()); }
    private String mailStatus(LlmApiKey key) { return jdbc.queryForObject("select status::text from notifications where public_id=?", String.class, notification(key)); }
    private JsonNode read(String path) throws Exception { return mapper.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + bearer)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }

    @TestConfiguration static class TestClock { @Bean @Primary MutableClock usageClock() { return new MutableClock(); } }
    static class MutableClock extends Clock {
        private Instant value = Instant.now();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return value; }
    }
}
