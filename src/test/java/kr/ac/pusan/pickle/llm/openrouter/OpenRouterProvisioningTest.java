package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.llm.LlmApiKeyService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The provisioning sweep and the revoke coupling, against a mocked OpenRouter:
 * a funded key gets its OpenRouter half (ciphertext stored, generation
 * bumped), a failure is recorded without spending a generation, and revoking
 * a provisioned key deletes the OpenRouter half after commit.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class OpenRouterProvisioningTest {

    @Autowired
    private LlmOpenRouterProvisioner provisioner;
    @Autowired
    private LlmApiKeyService keyService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CredentialCipher credentialCipher;
    @MockitoBean
    private OpenRouterClient client;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_api_keys");
        jdbcTemplate.update("""
                insert into llm_gateway_state (id, generation, service_enabled)
                values (true, 1, true)
                on conflict (id) do update set generation = 1
                """);
        when(client.configured()).thenReturn(true);
    }

    @Test
    void aFundedKeyGetsItsOpenrouterHalfAndTheDocumentMoves() {
        long keyId = insertKey(new BigDecimal("5.00"), "ACTIVE");
        when(client.createKey(anyString(), any(), any(), any()))
                .thenReturn(new OpenRouterClient.CreatedKey("hash-1", "sk-or-plain"));
        long before = generation();

        provisioner.sweep();

        String enc = jdbcTemplate.queryForObject(
                "select openrouter_key_enc from llm_api_keys where id = ?", String.class, keyId);
        assertThat(credentialCipher.decrypt(enc)).isEqualTo("sk-or-plain");
        assertThat(jdbcTemplate.queryForObject(
                "select openrouter_key_hash from llm_api_keys where id = ?", String.class, keyId))
                .isEqualTo("hash-1");
        assertThat(generation())
                .as("the credential changes the sync document, so the write must bump")
                .isGreaterThan(before);
    }

    @Test
    void aFailureIsRecordedWithoutSpendingAGeneration() {
        long keyId = insertKey(new BigDecimal("5.00"), "ACTIVE");
        when(client.createKey(anyString(), any(), any(), any()))
                .thenThrow(new OpenRouterException(503, "upstream unavailable"));
        long before = generation();

        provisioner.sweep();

        assertThat(jdbcTemplate.queryForObject(
                "select openrouter_last_error from llm_api_keys where id = ?",
                String.class, keyId)).contains("503");
        assertThat(jdbcTemplate.queryForObject(
                "select openrouter_key_hash from llm_api_keys where id = ?",
                String.class, keyId)).isNull();
        assertThat(generation())
                .as("nothing the document carries changed")
                .isEqualTo(before);
    }

    @Test
    void unfundedAndRevokedKeysAreNeverProvisioned() {
        insertKey(BigDecimal.ZERO, "ACTIVE");
        insertKey(new BigDecimal("5.00"), "REVOKED");

        provisioner.sweep();

        Mockito.verify(client, Mockito.never()).createKey(anyString(), any(), any(), any());
    }

    @Test
    void revokingAProvisionedKeyDeletesTheOpenrouterHalf() {
        long keyId = insertKey(new BigDecimal("5.00"), "ACTIVE");
        jdbcTemplate.update("""
                update llm_api_keys set openrouter_key_hash = 'hash-r',
                       openrouter_key_enc = ? where id = ?
                """, credentialCipher.encrypt("sk-or-r"), keyId);
        UUID publicId = jdbcTemplate.queryForObject(
                "select public_id from llm_api_keys where id = ?", UUID.class, keyId);
        AuthenticatedUser admin = new AuthenticatedUser(SeedFixtures.orgadminId(jdbcTemplate),
                UUID.randomUUID(), "admin@test", UserRole.SYS_ADMIN, null);

        keyService.revoke(admin, publicId);

        // The service method's transaction committed when it returned, so the
        // after-commit deletion has fired by now.
        verify(client).deleteKey("hash-r");
    }

    private long insertKey(BigDecimal creditLimit, String status) {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?)
                returning id
                """, Long.class, "금액 축 시험 " + unique);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, ownerId, "금액 축 시험", "or-" + unique);
        return jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, token_hash,
                                          token_prefix, status, credit_limit, created_by)
                values (?, ?, ?, ?, ?, 'pickle-aa', ?::llm_api_key_status, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + unique,
                String.format("%064x", requestId), status, creditLimit, ownerId);
    }

    private long generation() {
        return jdbcTemplate.queryForObject(
                "select generation from llm_gateway_state where id", Long.class);
    }
}
