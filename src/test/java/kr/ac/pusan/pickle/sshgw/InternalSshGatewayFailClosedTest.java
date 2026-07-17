package kr.ac.pusan.pickle.sshgw;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
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
import tools.jackson.databind.ObjectMapper;

/**
 * Fail-closed guarantee of the {@code /internal/**} chain (docs/api/internal.md,
 * docs/plan/07): when {@code PICKLE_SSHGW_TOKEN} is unset the filter must reject
 * <b>every</b> call rather than accept an empty bearer. A separate context from
 * {@link InternalSshGatewayRouteTest} because it needs the token blank at
 * startup — a mis-provisioned prod profile hands out no routes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InternalSshGatewayFailClosedTest {

    private static final String SSHGW_IP = "172.30.1.30";

    /** Blank the token that application-test.yml otherwise sets. */
    @DynamicPropertySource
    static void blankToken(DynamicPropertyRegistry registry) {
        registry.add("pickle.sshgw.token", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void blankServerTokenRejectsEvenAWellFormedBearerFromTheAllowedSource() throws Exception {
        // Allowed source + a plausible bearer: the ONLY reason this is denied is
        // the unset server token — proving the chain fails closed rather than
        // trusting an empty configured secret.
        mockMvc.perform(post("/internal/sshgw/route")
                        .with(request -> {
                            request.setRemoteAddr(SSHGW_IP);
                            return request;
                        })
                        .header("Authorization", "Bearer any-token-value")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("slug", "team-anything",
                                "sourceIp", "203.0.113.7", "authMethod", "password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }
}
