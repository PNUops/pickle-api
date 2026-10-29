package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * How the LLM gateway filter counts and caps the async-object ownership
 * calls: in memory rather than in {@code auth_rate_limits}, one bucket per
 * path, none at all for bind, and a body cap per path. The buckets live in
 * the filter instance, so this suite gets a context of its own with tiny
 * limits and gives it back when done.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LlmObjectLinkLimitsTest {

    private static final String SOURCE = "172.30.1.40";
    private static final String TOKEN = "test-llm-gateway-token";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pickle.llm-gateway.token", () -> TOKEN);
        registry.add("pickle.llm-gateway.object-intents-rate-limit-per-minute", () -> 2);
        // Lookup stays roomy: its separation is shown by not running out while
        // the intent bucket is spent, and the cap test below calls it too.
        registry.add("pickle.llm-gateway.object-lookup-rate-limit-per-minute", () -> 1000);
        registry.add("pickle.llm-gateway.object-release-rate-limit-per-minute", () -> 2);
        registry.add("pickle.llm-gateway.max-object-body-bytes", () -> 512);
        registry.add("pickle.llm-gateway.max-object-lookup-body-bytes", () -> 1024);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void eachCountedPathHasItsOwnBucketInMemoryAndBindHasNone() throws Exception {
        // Unknown keys are refused by the handlers; the filter has already
        // counted the call by then, which is all this test is about.
        Map<String, Object> intent = Map.of("keyId", UUID.randomUUID().toString(),
                "kind", "FILE", "eventId", "evt");
        Map<String, Object> lookup = Map.of("keyId", UUID.randomUUID().toString(),
                "kind", "FILE", "objectIds", java.util.List.of("file-a"));
        Map<String, Object> release = Map.of("keyId", UUID.randomUUID().toString(),
                "kind", "FILE", "objectId", "file-a", "reason", "DELETED");
        Map<String, Object> bind = Map.of("keyId", UUID.randomUUID().toString(),
                "intentId", UUID.randomUUID().toString(), "kind", "FILE", "objectId", "file-a");

        call("/internal/llm/objects/intents", intent).andExpect(status().isConflict());
        call("/internal/llm/objects/intents", intent).andExpect(status().isConflict());
        call("/internal/llm/objects/intents", intent).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"));

        // Separate buckets: the spent intent budget does not touch lookup.
        for (int i = 0; i < 3; i++) {
            call("/internal/llm/objects/lookup", lookup).andExpect(status().isOk());
        }

        call("/internal/llm/objects/release", release).andExpect(status().isOk());
        call("/internal/llm/objects/release", release).andExpect(status().isOk());
        call("/internal/llm/objects/release", release).andExpect(status().isTooManyRequests());

        for (int i = 0; i < 20; i++) {
            call("/internal/llm/objects/bind", bind).andExpect(status().isNotFound());
        }

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from auth_rate_limits where scope like 'llm_objects%'",
                Integer.class)).isZero();
    }

    @Test
    void bodyCapsArePerPath() throws Exception {
        // Bind is uncounted, so it shows the 512-byte cap that intents and
        // release share without spending their buckets. 600 bytes is over
        // that cap and under lookup's 1 KiB, where the call reaches the
        // handler and is refused for its missing members instead.
        String padded = "{\"agentVersion\":\"" + "x".repeat(600) + "\"}";
        raw("/internal/llm/objects/bind", padded).andExpect(status().isPayloadTooLarge());
        raw("/internal/llm/objects/lookup", padded).andExpect(status().isUnprocessableContent());
        raw("/internal/llm/objects/lookup", "{\"agentVersion\":\"" + "x".repeat(1100) + "\"}")
                .andExpect(status().isPayloadTooLarge());
    }

    private ResultActions call(String path, Object body) throws Exception {
        return raw(path, objectMapper.writeValueAsString(body));
    }

    private ResultActions raw(String path, String body) throws Exception {
        return mockMvc.perform(post(path)
                .with(request -> {
                    request.setRemoteAddr(SOURCE);
                    return request;
                })
                .header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }
}
