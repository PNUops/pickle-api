package kr.ac.pusan.pickle.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * A host with no body keyring configured. Its own class because
 * {@code @DynamicPropertySource} is per-class, and the property it needs to
 * withhold is exactly the one the sibling suite supplies.
 *
 * <p>What this pins is the difference between the two ways a missing key could
 * fail. Silently storing nothing is the intended one. Silently storing the
 * prompts in the clear is the one nobody would notice, because every other
 * assertion in the suite would still pass.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class LlmBodyKeyringUnconfiguredTest {

    private static final String SOURCE = "172.30.1.40";
    private static final String TOKEN = "test-llm-gateway-token";
    private static final String PROMPT_TEXT = "\uc8fc\ubbfc\ubc88\ud638\ub294 900101-1234567 \uc785\ub2c8\ub2e4";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pickle.llm-gateway.token", () -> TOKEN);
        // Both blank: the shape a host that never set the key up would have.
        registry.add("pickle.llm-body-keyring.write-key-id", () -> "");
        registry.add("pickle.llm-body-keyring.read-keys", () -> "");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void theApiStartsAndTheBatchIsAcceptedButNothingIsKept() throws Exception {
        // Starting at all is half the assertion: capture is an off-by-default
        // option, so its key must never be able to stop the service booting.
        // Only this suite's own table. Deleting the keys as well would make the
        // class order-dependent -- they are the parent of several tables, so
        // the delete would succeed or fail according to what an earlier class
        // left behind. Nothing here needs them gone.
        jdbcTemplate.update("delete from llm_request_bodies");
        UUID key = recordingKey();

        Map<String, Object> record = new HashMap<>();
        record.put("eventUuid", "evt-1");
        record.put("keyId", key.toString());
        record.put("requestedAt", "2026-09-04T11:03:57Z");
        record.put("request", List.of(Map.of("role", "user", "content", PROMPT_TEXT)));
        record.put("response", "answer");
        Map<String, Object> batch = new HashMap<>();
        batch.put("records", List.of(record));

        // 2xx, because a refusal would make the gateway drop the batch -- and
        // the outcome is the same either way, so there is nothing to gain by
        // making its queue stall over it.
        mockMvc.perform(post("/internal/llm/bodies")
                        .with(request -> {
                            request.setRemoteAddr(SOURCE);
                            return request;
                        })
                        .header("Authorization", "Bearer " + TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.skipped").value(1));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from llm_request_bodies", Long.class)).isZero();
    }

    private UUID recordingKey() {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?)
                returning id
                """, Long.class, "llm-body-nokey " + unique);
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, 'OWNER'::workspace_member_role)
                """, workspaceId, ownerId);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, ownerId, "llm-body-nokey", "llm-" + unique);
        long id = jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, token_hash,
                                          token_prefix, status, record_bodies, created_by)
                values (?, ?, ?, ?, ?, ?, 'ACTIVE'::llm_api_key_status, true, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + unique,
                "hash-" + unique, "pickle-" + unique.substring(0, 2), ownerId);
        return jdbcTemplate.queryForObject(
                "select public_id from llm_api_keys where id = ?", UUID.class, id);
    }
}
