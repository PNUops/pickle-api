package kr.ac.pusan.pickle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.mail.AsyncMailDispatcher;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MockMailSender;
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
import tools.jackson.databind.ObjectMapper;

/**
 * How an account made through Google — one that has never had a password —
 * gets one.
 *
 * <p>There used to be a {@code POST /me/password} for this. It could not ask
 * for a current password, so the sudo-mode gate on the controller was the whole
 * of its authorization, and when that gate was removed the endpoint was retired
 * with it rather than be left resting on a session cookie alone. The route is
 * the reset mail again, which proves control of the mailbox.
 *
 * <p>These tests pin that shape, not the old one: the endpoint is gone, a
 * session alone plants nothing, and the mail route still reaches an account
 * with a null hash. The last one is the load-bearing case — if the reset path
 * ever started requiring an existing password, Google-only accounts would have
 * no way to get one at all.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class PasswordSetTest {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");
    private static final String NEW_PASSWORD = "new-horse-battery-staple!";

    // A fresh address per test rather than deleting the previous one: a user
    // row accumulates foreign keys (refresh tokens, notifications, audit rows,
    // a personal workspace) and a teardown that chases them is a list that goes
    // stale every time one is added.
    private String passwordless;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private MockMailSender mockMailSender;

    @Autowired
    private AsyncMailDispatcher mailDispatcher;

    /**
     * 요청 제한은 IP 로도 잡히고 그 창이 머신 전역 127.0.0.1 에 공유된다. 로그인
     * 하나를 더 얹는 것만으로 다른 스위트의 로그인이 429 가 될 수 있어서(실제로
     * 배포 게이트에서 `RefreshCsrfTest` 가 그렇게 터졌다) 이 스위트는 자기 주소를
     * 쓴다. `GoogleOauthFlowTest` 가 같은 이유로 같은 일을 한다.
     */
    private static final java.util.concurrent.atomic.AtomicInteger ADDRESS =
            new java.util.concurrent.atomic.AtomicInteger();

    private String clientAddress;
    private String passwordlessToken;
    private long passwordlessId;

    private org.springframework.test.web.servlet.request.RequestPostProcessor fromThisCase() {
        return request -> {
            request.setRemoteAddr(clientAddress);
            return request;
        };
    }

    @BeforeEach
    void seedAGoogleOnlyAccount() {
        clientAddress = "10.98.0." + (ADDRESS.incrementAndGet() % 250 + 1);
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        passwordless = "set.password.none." + suffix + "@pusan.ac.kr";

        User none = new User(passwordless, null, "구글가입");
        none.setStatus(UserStatus.ACTIVE);
        none = userRepository.saveAndFlush(none);
        passwordlessId = none.getId();
        passwordlessToken = jwtService.createAccessToken(none);
    }

    @Test
    void theSetPasswordEndpointIsGone() throws Exception {
        // A borrowed session must not be able to plant a credential. The
        // request reaches /me/password, which still answers PUT (change), so
        // the refusal is 405 rather than 404 — either way nothing is written.
        mockMvc.perform(post("/api/v1/me/password")
                        .with(fromThisCase())
                        .header("Authorization", "Bearer " + passwordlessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("newPassword", NEW_PASSWORD))))
                .andExpect(status().isMethodNotAllowed());

        assertThat(userRepository.findById(passwordlessId).orElseThrow().hasPassword()).isFalse();
    }

    @Test
    void changingIsRefusedWithNothingToCompareAgainst() throws Exception {
        mockMvc.perform(put("/api/v1/me/password")
                        .with(fromThisCase())
                        .header("Authorization", "Bearer " + passwordlessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", "anything-at-all-1!",
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_NOT_SET"));

        assertThat(userRepository.findById(passwordlessId).orElseThrow().hasPassword()).isFalse();
    }

    @Test
    void theResetMailReachesAnAccountWithNoPassword() throws Exception {
        assertThat(userRepository.findById(passwordlessId).orElseThrow().hasPassword()).isFalse();

        // The request path must not filter on "has a password": that filter is
        // what would strand every Google-only account.
        mockMvc.perform(post("/api/v1/auth/password-reset")
                        .with(fromThisCase())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", passwordless))))
                .andExpect(status().isAccepted());
        assertThat(mailDispatcher.awaitIdle(Duration.ofSeconds(10)))
                .as("mail dispatcher drained").isTrue();

        MailMessage mail = mockMailSender.lastMessageTo(passwordless);
        assertThat(mail).as("reset mail for a password-less account").isNotNull();
        Matcher matcher = TOKEN_IN_LINK.matcher(mail.textBody());
        assertThat(matcher.find()).isTrue();

        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .with(fromThisCase())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", matcher.group(1), "newPassword", NEW_PASSWORD))))
                .andExpect(status().isOk());

        assertThat(userRepository.findById(passwordlessId).orElseThrow().hasPassword()).isTrue();
    }

    @Test
    void theNewPasswordThenWorksOnTheLoginForm() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-reset")
                        .with(fromThisCase())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", passwordless))))
                .andExpect(status().isAccepted());
        assertThat(mailDispatcher.awaitIdle(Duration.ofSeconds(10))).isTrue();

        Matcher matcher = TOKEN_IN_LINK.matcher(
                mockMailSender.lastMessageTo(passwordless).textBody());
        assertThat(matcher.find()).isTrue();
        mockMvc.perform(post("/api/v1/auth/password-reset/confirm")
                        .with(fromThisCase())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", matcher.group(1), "newPassword", NEW_PASSWORD))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromThisCase())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", passwordless, "password", NEW_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }
}
