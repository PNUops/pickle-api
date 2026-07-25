package kr.ac.pusan.pickle.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * End-to-end contract flow: signup → mail token → verify → login → /me →
 * refresh rotation → reuse detection (chain revocation) → logout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AuthFlowTest {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([A-Za-z0-9_-]+)");

    private static final String EMAIL = "flow.tester@pusan.ac.kr";
    private static final String PASSWORD = "Corr3ct-horse-battery!";

    /** Consent to both current (v1) documents — required for signup. */
    private static final Object FULL_CONSENTS = List.of(
            Map.of("docType", "TERMS_OF_SERVICE", "version", 1),
            Map.of("docType", "PRIVACY_POLICY", "version", 1));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MockMailSender mockMailSender;

    @Test
    void fullAuthLifecycle() throws Exception {
        // signup → 202 and a verification mail
        postJson("/api/v1/auth/signup",
                Map.of("email", EMAIL, "password", PASSWORD, "name", "홍길동", "consents", FULL_CONSENTS))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").isNotEmpty());

        // login before verification → 403 AUTH_EMAIL_NOT_VERIFIED
        postJson("/api/v1/auth/login", Map.of("email", EMAIL, "password", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_NOT_VERIFIED"));

        // token comes from the recorded mock mail, not from logs
        MailMessage mail = mockMailSender.lastMessageTo(EMAIL);
        assertThat(mail).as("verification mail recorded by MockMailSender").isNotNull();
        Matcher matcher = TOKEN_IN_LINK.matcher(mail.body());
        assertThat(matcher.find()).as("verification link with token in mail body").isTrue();
        String verificationToken = matcher.group(1);

        // duplicate signup → the very same 202 (no account enumeration); the
        // address that is already registered gets a notice mail, not an account
        postJson("/api/v1/auth/signup",
                Map.of("email", EMAIL, "password", PASSWORD, "name", "홍길동", "consents", FULL_CONSENTS))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").isNotEmpty());
        MailMessage notice = mockMailSender.lastMessageTo(EMAIL);
        assertThat(notice.subject()).contains("가입 안내");
        assertThat(notice.body()).contains("이미 가입된 계정");
        assertThat(notice.body()).as("a notice mail carries no token").doesNotContain("token=");

        // verify-email → 200, account ACTIVE + PERSONAL group
        postJson("/api/v1/auth/verify-email", Map.of("token", verificationToken))
                .andExpect(status().isOk());

        // single-use: same token again → 410 AUTH_VERIFICATION_TOKEN_EXPIRED
        postJson("/api/v1/auth/verify-email", Map.of("token", verificationToken))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("AUTH_VERIFICATION_TOKEN_EXPIRED"));

        // login → 200 with access token, refresh cookie and CSRF cookie per contract
        MvcResult login = postJson("/api/v1/auth/login", Map.of("email", EMAIL, "password", PASSWORD))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(EMAIL))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(cookie().exists("pickle_refresh"))
                .andExpect(cookie().exists("pickle_csrf"))
                .andReturn();
        List<String> setCookies = login.getResponse().getHeaders("Set-Cookie");
        assertThat(setCookies).anySatisfy(c -> assertThat(c)
                .contains("pickle_refresh=")
                .contains("Path=/api/v1/auth")
                .contains("Max-Age=1209600")
                .contains("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax"));
        // CSRF double-submit cookie: site-wide path, readable by console script
        assertThat(setCookies).anySatisfy(c -> assertThat(c)
                .contains("pickle_csrf=")
                .contains("Path=/;")
                .contains("Max-Age=1209600")
                .doesNotContain("HttpOnly")
                .contains("Secure")
                .contains("SameSite=Lax"));
        String accessToken = objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asString();
        Cookie firstRefresh = login.getResponse().getCookie("pickle_refresh");
        Cookie firstCsrf = login.getResponse().getCookie("pickle_csrf");

        // /me without token → 401 AUTH_TOKEN_INVALID
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));

        // /me with token → profile with PERSONAL/OWNER membership
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.orgId").value((Object) null))
                .andExpect(jsonPath("$.memberships[0].groupKind").value("PERSONAL"))
                .andExpect(jsonPath("$.memberships[0].role").value("OWNER"))
                .andExpect(jsonPath("$.memberships[0].groupName").value("홍길동"))
                // contract-gate carryover: fields reflect the 2FA/consent surface
                .andExpect(jsonPath("$.mfaEnabled").value(false))
                .andExpect(jsonPath("$.pendingConsents").isArray());

        // refresh (with CSRF double submit) → rotated refresh + reissued CSRF cookie
        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(firstRefresh, firstCsrf)
                        .header("X-Pickle-Csrf", firstCsrf.getValue()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(cookie().exists("pickle_refresh"))
                .andExpect(cookie().exists("pickle_csrf"))
                .andReturn();
        Cookie rotatedRefresh = refreshed.getResponse().getCookie("pickle_refresh");
        Cookie rotatedCsrf = refreshed.getResponse().getCookie("pickle_csrf");
        assertThat(rotatedRefresh.getValue()).isNotEqualTo(firstRefresh.getValue());
        assertThat(rotatedCsrf.getValue()).isNotEqualTo(firstCsrf.getValue());

        // reuse of the rotated-away token → 401 and the whole chain is revoked
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(firstRefresh, rotatedCsrf)
                        .header("X-Pickle-Csrf", rotatedCsrf.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(rotatedRefresh, rotatedCsrf)
                        .header("X-Pickle-Csrf", rotatedCsrf.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));

        // fresh login, then logout revokes the token and clears both cookies
        MvcResult secondLogin = postJson("/api/v1/auth/login", Map.of("email", EMAIL, "password", PASSWORD))
                .andExpect(status().isOk())
                .andReturn();
        Cookie secondRefresh = secondLogin.getResponse().getCookie("pickle_refresh");
        Cookie secondCsrf = secondLogin.getResponse().getCookie("pickle_csrf");

        MvcResult logout = mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(secondRefresh, secondCsrf)
                        .header("X-Pickle-Csrf", secondCsrf.getValue()))
                .andExpect(status().isNoContent())
                .andReturn();
        List<String> logoutCookies = logout.getResponse().getHeaders("Set-Cookie");
        assertThat(logoutCookies).anySatisfy(c -> assertThat(c)
                .contains("pickle_refresh=").contains("Max-Age=0"));
        assertThat(logoutCookies).anySatisfy(c -> assertThat(c)
                .contains("pickle_csrf=").contains("Max-Age=0"));
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(secondRefresh, secondCsrf)
                        .header("X-Pickle-Csrf", secondCsrf.getValue()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_TOKEN_INVALID"));

        // logout is idempotent — no refresh cookie (but valid CSRF pair) still 204
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(secondCsrf)
                        .header("X-Pickle-Csrf", secondCsrf.getValue()))
                .andExpect(status().isNoContent());
    }

    @Test
    void signupRequiresConsentToEveryCurrentDocument() throws Exception {
        // Only one of the two required documents → 422 (server completeness check).
        Map<String, ?> partial = Map.of("email", "consent.tester@pusan.ac.kr", "password", PASSWORD,
                "name", "동의자",
                "consents", List.of(Map.of("docType", "TERMS_OF_SERVICE", "version", 1)));
        postJson("/api/v1/auth/signup", partial)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // both documents → 202
        Map<String, ?> full = Map.of("email", "consent.tester@pusan.ac.kr", "password", PASSWORD,
                "name", "동의자", "consents", FULL_CONSENTS);
        postJson("/api/v1/auth/signup", full)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void signupRejectsNonPusanEmail() throws Exception {
        postJson("/api/v1/auth/signup",
                Map.of("email", "someone@gmail.com", "password", PASSWORD, "name", "외부인",
                        "consents", FULL_CONSENTS))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
    }

    @Test
    void signupRejectsWeakPassword() throws Exception {
        // long enough but a well-known password → server-side policy rejects
        postJson("/api/v1/auth/signup",
                Map.of("email", "weak.password@pusan.ac.kr", "password", "qwerty1234", "name", "약한비번",
                        "consents", FULL_CONSENTS))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));

        // too short → bean validation
        postJson("/api/v1/auth/signup",
                Map.of("email", "weak.password@pusan.ac.kr", "password", "short1!", "name", "약한비번",
                        "consents", FULL_CONSENTS))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String uri, Map<String, ?> body)
            throws Exception {
        return mockMvc.perform(post(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
