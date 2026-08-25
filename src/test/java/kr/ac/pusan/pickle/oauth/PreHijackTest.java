package kr.ac.pusan.pickle.oauth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.identity.UserIdentityRepository;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.GoogleOauthWireMockSupport;
import kr.ac.pusan.pickle.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/** Reproduction of the account-pre-hijacking shape end to end. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class PreHijackTest {

    private static final GoogleOauthWireMockSupport GOOGLE = GoogleOauthWireMockSupport.start();
    private static final String VICTIM = "prehijack.victim@pusan.ac.kr";
    private static final String ATTACKER_PASSWORD = "Attacker-knows-this-9!";

    @DynamicPropertySource
    static void googleProperties(DynamicPropertyRegistry registry) {
        registry.add("pickle.oauth.google.client-id", () -> GoogleOauthWireMockSupport.CLIENT_ID);
        registry.add("pickle.oauth.google.client-secret", () -> "test-client-secret");
        registry.add("pickle.oauth.google.token-uri", GOOGLE::tokenUri);
        registry.add("pickle.oauth.google.jwk-set-uri", GOOGLE::jwkSetUri);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private UserIdentityRepository identityRepository;

    @Test
    void aPasswordSetBeforeTheOwnerEverArrivedMustNotSurviveTheGoogleSignIn() throws Exception {
        // 1. The attacker signs the victim's address up with a password only they
        //    know. The verification mail goes to the victim, so it stays PENDING.
        mockMvc.perform(post("/api/v1/auth/signup")
                        .with(r -> { r.setRemoteAddr("10.98.0.1"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", VICTIM, "password", ATTACKER_PASSWORD, "name", "공격자",
                                "position", "STUDENT_UNDERGRAD", "studentNo", "202000000",
                                "departmentCode", "COMPUTER_SCIENCE",
                                "consents", List.of(
                                        Map.of("docType", "TERMS_OF_SERVICE", "version", 1),
                                        Map.of("docType", "PRIVACY_POLICY", "version", 1))))))
                .andExpect(status().isAccepted());

        // 2. The victim signs in with Google for the first time. This activates
        //    the account and links the identity.
        MvcResult started = mockMvc.perform(post("/api/v1/auth/oauth/google/start")
                        .with(r -> { r.setRemoteAddr("10.98.0.2"); return r; })
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn();
        var body = objectMapper.readTree(started.getResponse().getContentAsString());
        String nonce = java.net.URLDecoder.decode(
                body.get("authorizationUrl").asString().replaceAll(".*[?&]nonce=([^&]*).*", "$1"),
                java.nio.charset.StandardCharsets.UTF_8);
        GOOGLE.stubToken(GoogleOauthWireMockSupport.claims("sub-prehijack", VICTIM, nonce));
        mockMvc.perform(post("/api/v1/auth/oauth/google/callback")
                        .with(r -> { r.setRemoteAddr("10.98.0.2"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("code", "stub-code", "state", body.get("state").asString()))))
                .andExpect(status().isOk());

        // 3. The attacker logs in with the password they set. This must NOT work:
        //    nobody ever proved they owned the mailbox that password was set from.
        mockMvc.perform(post("/api/v1/auth/login")
                        .with(r -> { r.setRemoteAddr("10.98.0.3"); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", VICTIM, "password", ATTACKER_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
    }
}
