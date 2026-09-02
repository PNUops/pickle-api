package kr.ac.pusan.pickle.llm.openrouter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.ac.pusan.pickle.common.crypto.CredentialCipher;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmApiKeyService;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    @Autowired
    private OpenRouterManagementCredentialCipher managementCipher;
    @MockitoBean
    private OpenRouterClient client;

    /** The management credential this test's account scope decrypts to. */
    private static final String ACCOUNT_KEY = "provisioner-account-management-key";

    private long accountId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_credit_usage_snapshots");
        jdbcTemplate.update("delete from llm_api_keys");
        jdbcTemplate.update("delete from openrouter_credit_snapshots");
        jdbcTemplate.update("delete from openrouter_account_credentials");
        jdbcTemplate.update("delete from openrouter_accounts");
        jdbcTemplate.update("""
                insert into llm_gateway_state (id, generation, service_enabled)
                values (true, 1, true)
                on conflict (id) do update set generation = 1
                """);
        accountId = insertAccount();
    }

    /** The account every key here is funded by; it has no vendor workspace. */
    private long insertAccount() {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        long id = jdbcTemplate.queryForObject("""
                insert into openrouter_accounts (org_id, name, created_by)
                values (?, ?, ?)
                returning id
                """, Long.class, orgId, "발급 시험 사업 " + UUID.randomUUID(), ownerId);
        UUID publicId = jdbcTemplate.queryForObject(
                "select public_id from openrouter_accounts where id = ?", UUID.class, id);
        jdbcTemplate.update("""
                insert into openrouter_account_credentials
                       (account_id, status, credential_enc, created_by,
                        activated_at, verified_at)
                values (?, 'ACTIVE'::openrouter_credential_status, ?, ?, now(), now())
                """, id, managementCipher.encrypt(publicId, ACCOUNT_KEY), ownerId);
        return id;
    }

    @Test
    void aFundedKeyGetsItsOpenrouterHalfAndTheDocumentMoves() {
        long keyId = insertKey(new BigDecimal("5.00"), "ACTIVE");
        Instant expiresAt = Instant.parse("2026-12-31T00:00:00Z");
        jdbcTemplate.update("update llm_api_keys set credit_limit_reset = 'MONTHLY', "
                + "expires_at = ? where id = ?", java.sql.Timestamp.from(expiresAt), keyId);
        when(client.createKey(anyString(), any(), anyString(), any(), any(), any()))
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
        // What was asked of OpenRouter, not just that something was: the
        // granted amount and window, and the pickle key's own expiry — that
        // last one is what stops an expired key from spending money on the
        // remote side after our document has stopped serving its credential.
        ArgumentCaptor<BigDecimal> limit = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
        verify(client).createKey(eq(ACCOUNT_KEY), eq(null),
                eq(publicIdOf(keyId).toString()), limit.capture(),
                eq(CreditLimitReset.MONTHLY), expiry.capture());
        assertThat(limit.getValue()).isEqualByComparingTo("5.00");
        assertThat(expiry.getValue()).isEqualTo(expiresAt);
    }

    @Test
    void aFailureIsRecordedWithoutSpendingAGeneration() {
        long keyId = insertKey(new BigDecimal("5.00"), "ACTIVE");
        when(client.createKey(anyString(), any(), anyString(), any(), any(), any()))
                .thenThrow(new OpenRouterException(503, "upstream unavailable"));
        long before = generation();

        provisioner.sweep();

        assertThat(jdbcTemplate.queryForObject(
                "select openrouter_last_error from llm_api_keys where id = ?",
                String.class, keyId)).isEqualTo("VENDOR_UNAVAILABLE");
        assertThat(jdbcTemplate.queryForObject(
                "select openrouter_key_hash from llm_api_keys where id = ?",
                String.class, keyId)).isNull();
        assertThat(generation())
                .as("nothing the document carries changed")
                .isEqualTo(before);
    }

    @Test
    void ineligibleKeysAreRejectedByBothSweepAndDirectEntry() {
        insertKey(BigDecimal.ZERO, "ACTIVE");
        long revoked = insertKey(new BigDecimal("5.00"), "REVOKED");
        long suspended = insertKey(new BigDecimal("5.00"), "SUSPENDED");
        long expired = insertKey(new BigDecimal("5.00"), "ACTIVE");
        jdbcTemplate.update("update llm_api_keys set expires_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(1)), expired);

        provisioner.provision(revoked);
        provisioner.provision(suspended);
        provisioner.provision(expired);
        provisioner.sweep();

        Mockito.verify(client, Mockito.never()).createKey(
                anyString(), any(), anyString(), any(), any(), any());
    }

    @Test
    void concurrentPolicyChangeDeletesTheStaleRemoteKeyWithoutRecordingIt() throws Exception {
        long keyId = insertKey(new BigDecimal("5.00"), "ACTIVE");
        CountDownLatch createStarted = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        when(client.createKey(anyString(), any(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    createStarted.countDown();
                    if (!releaseCreate.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test create release timed out");
                    }
                    return new OpenRouterClient.CreatedKey("hash-race", "runtime-race");
                });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var provisioning = executor.submit(() -> provisioner.provision(keyId));
            assertThat(createStarted.await(5, TimeUnit.SECONDS)).isTrue();
            jdbcTemplate.update("""
                    update llm_api_keys
                       set status = 'SUSPENDED'::llm_api_key_status,
                           credit_limit = 7,
                           credit_limit_reset = 'MONTHLY'
                     where id = ?
                    """, keyId);
            releaseCreate.countDown();
            provisioning.get(5, TimeUnit.SECONDS);

            verify(client).deleteKey(ACCOUNT_KEY, null, "hash-race");
            assertThat(jdbcTemplate.queryForObject(
                    "select openrouter_key_hash from llm_api_keys where id = ?",
                    String.class, keyId)).isNull();
            assertThat(jdbcTemplate.queryForObject(
                    "select openrouter_last_error from llm_api_keys where id = ?",
                    String.class, keyId)).isNull();
        } finally {
            releaseCreate.countDown();
            executor.shutdownNow();
        }
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
                UUID.randomUUID(), "admin@test", UserRole.SYS_ADMIN, java.util.Map.of());

        keyService.revoke(admin, publicId);

        // The service method's transaction committed when it returned, so the
        // after-commit deletion has fired by now.
        verify(client).deleteKey(ACCOUNT_KEY, null, "hash-r");
    }

    @Test
    void stalePostCommitCallbacksPushTheLatestDatabaseState() {
        long keyId = insertKey(new BigDecimal("7.00"), "ACTIVE");
        jdbcTemplate.update("""
                update llm_api_keys
                   set openrouter_key_hash = 'hash-current', openrouter_key_enc = ?,
                       credit_limit_reset = 'MONTHLY'
                 where id = ?
                """, credentialCipher.encrypt("runtime-current"), keyId);

        provisioner.updateLimitAfterChange(keyId, "hash-stale", new BigDecimal("2.00"), null);
        provisioner.setDisabledAfterStatusChange(keyId, "hash-stale", true);

        verify(client).updateLimit(ACCOUNT_KEY, null,
                "hash-current", new BigDecimal("7.00"), CreditLimitReset.MONTHLY);
        verify(client).setDisabled(ACCOUNT_KEY, null,
                "hash-current", false);
    }

    @Test
    void lateLimitCallbackReappliesTheLatestDatabaseState() throws Exception {
        long keyId = insertKey(new BigDecimal("2.00"), "ACTIVE");
        jdbcTemplate.update("""
                update llm_api_keys
                   set openrouter_key_hash = 'hash-converge', openrouter_key_enc = ?
                 where id = ?
                """, credentialCipher.encrypt("runtime-converge"), keyId);
        CountDownLatch oldCallStarted = new CountDownLatch(1);
        CountDownLatch releaseOldCall = new CountDownLatch(1);
        List<BigDecimal> vendorWrites = new CopyOnWriteArrayList<>();
        Mockito.doAnswer(invocation -> {
            BigDecimal limit = invocation.getArgument(3);
            vendorWrites.add(limit);
            if (limit.compareTo(new BigDecimal("2.00")) == 0) {
                oldCallStarted.countDown();
                if (!releaseOldCall.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test limit release timed out");
                }
            }
            return null;
        }).when(client).updateLimit(anyString(), any(), eq("hash-converge"), any(), any());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var oldCallback = executor.submit(() -> provisioner.updateLimitAfterChange(
                    keyId, "hash-converge", new BigDecimal("2.00"), null));
            assertThat(oldCallStarted.await(5, TimeUnit.SECONDS)).isTrue();

            jdbcTemplate.update("""
                    update llm_api_keys
                       set credit_limit = 7, credit_limit_reset = 'MONTHLY'
                     where id = ?
                    """, keyId);
            provisioner.updateLimitAfterChange(
                    keyId, "hash-converge", new BigDecimal("7.00"), CreditLimitReset.MONTHLY);

            releaseOldCall.countDown();
            oldCallback.get(5, TimeUnit.SECONDS);
            assertThat(vendorWrites).usingElementComparator(BigDecimal::compareTo)
                    .containsExactly(new BigDecimal("2.00"), new BigDecimal("7.00"),
                            new BigDecimal("7.00"));
        } finally {
            releaseOldCall.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void lateStatusCallbackReappliesTheLatestDatabaseState() throws Exception {
        long keyId = insertKey(new BigDecimal("2.00"), "ACTIVE");
        jdbcTemplate.update("""
                update llm_api_keys
                   set openrouter_key_hash = 'hash-status', openrouter_key_enc = ?
                 where id = ?
                """, credentialCipher.encrypt("runtime-status"), keyId);
        CountDownLatch activeCallStarted = new CountDownLatch(1);
        CountDownLatch releaseActiveCall = new CountDownLatch(1);
        List<Boolean> vendorWrites = new CopyOnWriteArrayList<>();
        Mockito.doAnswer(invocation -> {
            boolean disabled = invocation.getArgument(3);
            vendorWrites.add(disabled);
            if (!disabled) {
                activeCallStarted.countDown();
                if (!releaseActiveCall.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test status release timed out");
                }
            }
            return null;
        }).when(client).setDisabled(anyString(), any(), eq("hash-status"), anyBoolean());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var oldCallback = executor.submit(() -> provisioner.setDisabledAfterStatusChange(
                    keyId, "hash-status", false));
            assertThat(activeCallStarted.await(5, TimeUnit.SECONDS)).isTrue();

            jdbcTemplate.update("""
                    update llm_api_keys
                       set status = 'SUSPENDED'::llm_api_key_status
                     where id = ?
                    """, keyId);
            provisioner.setDisabledAfterStatusChange(keyId, "hash-status", true);

            releaseActiveCall.countDown();
            oldCallback.get(5, TimeUnit.SECONDS);
            assertThat(vendorWrites).containsExactly(false, true, true);
        } finally {
            releaseActiveCall.countDown();
            executor.shutdownNow();
        }
    }

    private long insertKey(BigDecimal creditLimit, String status) {
        long orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        long ownerId = SeedFixtures.orgadminId(jdbcTemplate);
        String unique = UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?)
                returning id
                """, Long.class, "유료 모델 시험 " + unique);
        long requestId = jdbcTemplate.queryForObject("""
                insert into requests (resource_type, workspace_id, org_id, requester_id,
                                      purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, ownerId, "유료 모델 시험", "or-" + unique);
        return jdbcTemplate.queryForObject("""
                insert into llm_api_keys (workspace_id, org_id, request_id, name, token_hash,
                                          token_prefix, status, credit_limit,
                                          openrouter_account_id, created_by)
                values (?, ?, ?, ?, ?, 'pickle-aa', ?::llm_api_key_status, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + unique,
                String.format("%064x", requestId), status, creditLimit, accountId, ownerId);
    }

    private UUID publicIdOf(long keyId) {
        return jdbcTemplate.queryForObject(
                "select public_id from llm_api_keys where id = ?", UUID.class, keyId);
    }

    private long generation() {
        return jdbcTemplate.queryForObject(
                "select generation from llm_gateway_state where id", Long.class);
    }
}
