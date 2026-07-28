package kr.ac.pusan.pickle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.mail.AsyncMailDispatcher;
import kr.ac.pusan.pickle.mail.MailMessage;
import kr.ac.pusan.pickle.mail.MockMailSender;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/**
 * CSRF double-submit enforcement on the cookie-authed endpoints
 * (contract v0.3.1: 403 {@code AUTH_CSRF_INVALID} on POST /auth/refresh and
 * /auth/logout when the {@code X-Pickle-Csrf} header is missing or does not
 * match the {@code __Host-pickle_csrf} cookie). Bearer/anonymous endpoints are not
 * affected.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class RefreshCsrfTest {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

    private static final String PASSWORD = "Corr3ct-horse-battery!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMailSender mockMailSender;

    @Autowired
    private AsyncMailDispatcher mailDispatcher;

    @Test
    void refreshAndLogoutRequireMatchingCsrfPair() throws Exception {
        MvcResult login = loginWithFreshAccount("csrf.tester@pusan.ac.kr");
        Cookie refresh = login.getResponse().getCookie("__Host-pickle_refresh");
        Cookie csrf = login.getResponse().getCookie("__Host-pickle_csrf");
        assertThat(csrf).as("__Host-pickle_csrf cookie issued on login").isNotNull();

        // header missing → 403 AUTH_CSRF_INVALID (problem+json)
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refresh, csrf))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"))
                .andExpect(jsonPath("$.status").value(403));

        // header does not match the cookie → 403
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refresh, csrf)
                        .header("X-Pickle-Csrf", "not-the-cookie-value"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));

        // cookie missing (header alone) → 403
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refresh)
                        .header("X-Pickle-Csrf", csrf.getValue()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));

        // logout without the pair → 403 as well
        mockMvc.perform(post("/api/v1/auth/logout").cookie(refresh, csrf))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));

        // the rejected attempts must not have consumed the refresh token:
        // matching pair → 200 with rotated refresh cookie
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refresh, csrf)
                        .header("X-Pickle-Csrf", csrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(cookie().exists("__Host-pickle_refresh"))
                .andExpect(cookie().exists("__Host-pickle_csrf"));
    }

    /**
     * Two cookies of the CSRF name in one request are rejected outright. A client
     * that honours the {@code __Host-} prefix cannot produce this, but one that
     * does not could be handed a second, attacker-set copy; picking either value
     * silently is exactly how a shadowing cookie would get through, so the
     * ambiguity itself is the failure.
     */
    @Test
    void duplicateCsrfCookiesAreRejected() throws Exception {
        MvcResult login = loginWithFreshAccount("csrf.dupe@pusan.ac.kr");
        Cookie refresh = login.getResponse().getCookie("__Host-pickle_refresh");
        Cookie csrf = login.getResponse().getCookie("__Host-pickle_csrf");

        Cookie shadow = new Cookie("__Host-pickle_csrf", "attacker-planted-value");
        // the header echoes the genuine value, so only the duplicate can fail this
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refresh, csrf, shadow)
                        .header("X-Pickle-Csrf", csrf.getValue()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));

        // ... and the ordering does not matter: the planted copy first is also 403
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refresh, shadow, csrf)
                        .header("X-Pickle-Csrf", csrf.getValue()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_CSRF_INVALID"));

        // the rejections consumed nothing: the genuine single pair still refreshes
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(refresh, csrf)
                        .header("X-Pickle-Csrf", csrf.getValue()))
                .andExpect(status().isOk());
    }

    @Test
    void otherAuthEndpointsDoNotRequireCsrf() throws Exception {
        // login (and the signup flow inside the helper) succeed without any
        // X-Pickle-Csrf header: only refresh/logout are double-submit-guarded.
        loginWithFreshAccount("csrf.bystander@pusan.ac.kr");
    }

    /** signup → verification mail token → verify-email → login (no CSRF anywhere). */
    private MvcResult loginWithFreshAccount(String email) throws Exception {
        postJson("/api/v1/auth/signup", Map.of("email", email, "password", PASSWORD, "name", "시에스알프",
                "consents", java.util.List.of(
                        Map.of("docType", "TERMS_OF_SERVICE", "version", 1),
                        Map.of("docType", "PRIVACY_POLICY", "version", 1))))
                .andExpect(status().isAccepted());
        assertThat(mailDispatcher.awaitIdle(Duration.ofSeconds(10)))
                .as("mail dispatcher drained").isTrue();
        MailMessage mail = mockMailSender.lastMessageTo(email);
        assertThat(mail).as("verification mail recorded by MockMailSender").isNotNull();
        Matcher matcher = TOKEN_IN_LINK.matcher(mail.body());
        assertThat(matcher.find()).as("verification link with token in mail body").isTrue();
        postJson("/api/v1/auth/verify-email", Map.of("token", matcher.group(1)))
                .andExpect(status().isOk());
        return postJson("/api/v1/auth/login", Map.of("email", email, "password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("__Host-pickle_refresh"))
                .andExpect(cookie().exists("__Host-pickle_csrf"))
                .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String uri, Map<String, ?> body)
            throws Exception {
        return mockMvc.perform(post(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
