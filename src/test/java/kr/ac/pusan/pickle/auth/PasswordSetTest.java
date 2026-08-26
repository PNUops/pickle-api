package kr.ac.pusan.pickle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /me/password} — the first password on an account that has never
 * had one, i.e. every account made through Google.
 *
 * <p>Before this endpoint the only route was to mail a reset link to someone
 * who was already signed in and ask them to come back through it, which is
 * also why the six operations that ask for a current password were unreachable
 * for those accounts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class PasswordSetTest {

    // A fresh address per test rather than deleting the previous one: a user
    // row accumulates foreign keys (reverifications, refresh tokens,
    // notifications, audit rows, a personal workspace) and a teardown that
    // chases them is a list that goes stale every time one is added.
    private String passwordless;
    private String withPassword;
    /** Same bcrypt fixture the other suites use; the cleartext is irrelevant. */
    private static final String SOME_HASH = "$2a$12$C6UzMDM.H6dfI/f/IKcEeO7uHhZ8mCEyXbNP9qhrPQicvBSl2Fx16";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtService jwtService;

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
    private String withPasswordToken;

    private org.springframework.test.web.servlet.request.RequestPostProcessor fromThisCase() {
        return request -> {
            request.setRemoteAddr(clientAddress);
            return request;
        };
    }

    @BeforeEach
    void seedBothShapes() {
        clientAddress = "10.98.0." + (ADDRESS.incrementAndGet() % 250 + 1);
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        passwordless = "set.password.none." + suffix + "@pusan.ac.kr";
        withPassword = "set.password.has." + suffix + "@pusan.ac.kr";

        User none = new User(passwordless, null, "구글가입");
        none.setStatus(UserStatus.ACTIVE);
        none = userRepository.saveAndFlush(none);
        passwordlessId = none.getId();
        passwordlessToken = jwtService.createAccessToken(none);

        User has = new User(withPassword, SOME_HASH, "비밀번호있음");
        has.setStatus(UserStatus.ACTIVE);
        withPasswordToken = jwtService.createAccessToken(userRepository.saveAndFlush(has));
    }

    @Test
    void aGoogleAccountSetsItsFirstPassword() throws Exception {
        assertThat(userRepository.findById(passwordlessId).orElseThrow().hasPassword()).isFalse();

        mockMvc.perform(setPassword(passwordlessToken,
                        ReauthTestSupport.seededReauthHeader(jdbcTemplate, passwordlessId),
                        "new-horse-battery-staple!"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        assertThat(userRepository.findById(passwordlessId).orElseThrow().hasPassword()).isTrue();
    }

    @Test
    void everyOtherSessionDiesWithIt() throws Exception {
        mockMvc.perform(setPassword(passwordlessToken,
                        ReauthTestSupport.seededReauthHeader(jdbcTemplate, passwordlessId),
                        "new-horse-battery-staple!"))
                .andExpect(status().isOk());

        // A password appearing on an account is a session-invalidating event
        // whether or not one was there before: the bearer token that made the
        // call is itself dead afterwards, and the caller continues on the pair
        // the response carried.
        mockMvc.perform(get("/api/v1/me").with(fromThisCase())
                        .header("Authorization", "Bearer " + passwordlessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withoutReauthItIsRefused() throws Exception {
        // The gate is the whole of the authorization here — no current password
        // is asked for, so an access token alone must not be enough.
        mockMvc.perform(post("/api/v1/me/password")
                        .with(fromThisCase())
                        .header("Authorization", "Bearer " + passwordlessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("newPassword", "new-horse-battery-staple!"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REAUTH_REQUIRED"));

        assertThat(userRepository.findById(passwordlessId).orElseThrow().hasPassword()).isFalse();
    }

    @Test
    void anAccountThatAlreadyHasOneIsSentToTheChangeEndpoint() throws Exception {
        long id = userRepository.findByEmail(withPassword).orElseThrow().getId();
        mockMvc.perform(setPassword(withPasswordToken,
                        ReauthTestSupport.seededReauthHeader(jdbcTemplate, id),
                        "new-horse-battery-staple!"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_PASSWORD_ALREADY_SET"));
    }

    @Test
    void theSamePolicySignupAppliesIsApplied() throws Exception {
        mockMvc.perform(setPassword(passwordlessToken,
                        ReauthTestSupport.seededReauthHeader(jdbcTemplate, passwordlessId), "aaaaaaaa"))
                .andExpect(status().isUnprocessableContent());

        assertThat(userRepository.findById(passwordlessId).orElseThrow().hasPassword()).isFalse();
    }

    @Test
    void theNewPasswordThenWorksOnTheLoginForm() throws Exception {
        mockMvc.perform(setPassword(passwordlessToken,
                        ReauthTestSupport.seededReauthHeader(jdbcTemplate, passwordlessId),
                        "new-horse-battery-staple!"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .with(fromThisCase())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", passwordless, "password", "new-horse-battery-staple!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder setPassword(
            String accessToken, String reauthToken, String newPassword) throws Exception {
        return post("/api/v1/me/password")
                .with(fromThisCase())
                .header("Authorization", "Bearer " + accessToken)
                .header(ReauthTestSupport.HEADER, reauthToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("newPassword", newPassword)));
    }
}
