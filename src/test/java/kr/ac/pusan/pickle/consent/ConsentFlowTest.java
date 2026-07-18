package kr.ac.pusan.pickle.consent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** M6 W2-A: /me pendingConsents and GET/POST /me/consents. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ConsentFlowTest {

    private static final String PASSWORD = "Corr3ct-horse-battery!";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired
    private JwtService jwtService;

    @Test
    void newUserHasPendingConsentsThenClearsAfterAccepting() throws Exception {
        // A user created after the V42 backfill has no consent rows yet.
        User user = createActiveUser("consent.flow@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingConsents.length()").value(2));
        mockMvc.perform(get("/api/v1/me/consents").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // accept both current (v1) documents
        postConsents("/api/v1/me/consents", access, Map.of("consents", List.of(
                Map.of("docType", "TERMS_OF_SERVICE", "version", 1),
                Map.of("docType", "PRIVACY_POLICY", "version", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // pending now empty, history has both
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + access))
                .andExpect(jsonPath("$.pendingConsents.length()").value(0));
        mockMvc.perform(get("/api/v1/me/consents").header("Authorization", "Bearer " + access))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void staleVersionIsRejected() throws Exception {
        User user = createActiveUser("consent.stale@pusan.ac.kr");
        String access = jwtService.createAccessToken(user);
        postConsents("/api/v1/me/consents", access, Map.of("consents", List.of(
                Map.of("docType", "TERMS_OF_SERVICE", "version", 99))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONSENT_VERSION_MISMATCH"));
    }

    private User createActiveUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, passwordEncoder.encode(PASSWORD), "동의자");
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    private ResultActions postConsents(String path, String access, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(path)
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
