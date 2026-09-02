package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.admin.dto.ConfirmOpenRouterAccountRequest;
import kr.ac.pusan.pickle.admin.dto.CreateOpenRouterAccountRequest;
import kr.ac.pusan.pickle.admin.dto.FinalizeOpenRouterCredentialRequest;
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeyLimitsRequest;
import kr.ac.pusan.pickle.admin.dto.OpenRouterAccountResponse;
import kr.ac.pusan.pickle.admin.dto.StageOpenRouterCredentialRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateOpenRouterAccountRequest;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.llm.LlmKeyRequestSupport;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.dto.ApproveLlmKeyRequestSpec;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountCredential;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountCredentialRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccount;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountStatus;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountSelectionService;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterClient;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCredentialStatus;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCredentialError;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCredentialResolver;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterException;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterManagementCredentialCipher;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterManagementAccess;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminOpenRouterAccountTest {

    private static final UUID VENDOR_WORKSPACE =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final String MANAGEMENT_KEY = "management-key-must-never-leave-request";

    @Autowired private AdminOpenRouterAccountService service;
    @Autowired private OpenRouterAccountRepository accountRepository;
    @MockitoSpyBean private OpenRouterAccountSelectionService selectionService;
    @Autowired private OpenRouterAccountCredentialRepository credentialRepository;
    @Autowired private OpenRouterCredentialResolver credentialResolver;
    @Autowired private LlmApiKeyRepository keyRepository;
    @Autowired private OpenRouterManagementCredentialCipher managementCipher;
    @Autowired private AdminLlmKeyService adminLlmKeyService;
    @Autowired private LlmKeyRequestSupport requestSupport;
    @Autowired private RequestRepository requestRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private OpenRouterClient client;

    private long orgId;
    private UUID orgPublicId;
    private AuthenticatedUser sysAdmin;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from llm_usage_events");
        jdbcTemplate.update("delete from llm_credit_usage_snapshots");
        jdbcTemplate.update("delete from llm_api_keys");
        jdbcTemplate.update("delete from llm_key_request_details");
        jdbcTemplate.update("delete from openrouter_account_credentials");
        jdbcTemplate.update("delete from openrouter_accounts");
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        orgPublicId = jdbcTemplate.queryForObject(
                "select public_id from orgs where id = ?", UUID.class, orgId);
        long adminId = SeedFixtures.sysadminId(jdbcTemplate);
        sysAdmin = new AuthenticatedUser(adminId, UUID.randomUUID(), "sys@test",
                UserRole.SYS_ADMIN, Map.of());

        when(client.credits(anyString())).thenReturn(
                new OpenRouterClient.Credits(BigDecimal.TEN, BigDecimal.ZERO));
        when(client.listKeys(anyString(), isNull())).thenReturn(List.of());
        when(client.listKeys(anyString(), any(UUID.class))).thenReturn(List.of());
        when(client.createKey(anyString(), any(), anyString(), eq(BigDecimal.ZERO),
                isNull(), any(Instant.class)))
                .thenReturn(new OpenRouterClient.CreatedKey(
                        "probe-hash", "probe-runtime-secret", VENDOR_WORKSPACE));
        when(client.createKey(anyString(), any(UUID.class), anyString(),
                eq(BigDecimal.ZERO), isNull(), isNull()))
                .thenAnswer(invocation -> new OpenRouterClient.CreatedKey(
                        "identity-" + UUID.randomUUID(), "identity-runtime-secret",
                        invocation.getArgument(1)));
    }

    @Test
    void stagePersistsDiscoveredWorkspaceAndResponseNeverContainsCredentialMaterial() {
        OpenRouterAccountResponse created = create("사업 A");
        assertThat(created.eligibleForBinding()).isFalse();
        assertThatThrownBy(() -> selectionService.select(orgId, BigDecimal.ONE, created.id()))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.getErrors().getFirst().message())
                                .contains("관리용 키"));

        OpenRouterAccountResponse staged = service.stage(sysAdmin, created.id(),
                new StageOpenRouterCredentialRequest(MANAGEMENT_KEY, "사업 A"), "127.0.0.1");

        assertThat(staged.rotationCredential().status())
                .isEqualTo(OpenRouterCredentialStatus.STAGED);
        assertThat(jdbcTemplate.queryForObject(
                "select vendor_workspace_id from openrouter_accounts where public_id = ?",
                UUID.class, created.id())).isEqualTo(VENDOR_WORKSPACE);
        String ciphertext = jdbcTemplate.queryForObject(
                "select credential_enc from openrouter_account_credentials", String.class);
        assertThat(ciphertext).startsWith("or-mgmt-v1:").doesNotContain(MANAGEMENT_KEY);
        assertThat(objectMapper.writeValueAsString(staged))
                .doesNotContain(MANAGEMENT_KEY)
                .doesNotContain("probe-runtime-secret")
                .doesNotContain("credentialEnc")
                .doesNotContain(VENDOR_WORKSPACE.toString());

        OpenRouterAccountResponse active = service.activate(sysAdmin, created.id(),
                new ConfirmOpenRouterAccountRequest("사업 A"), "127.0.0.1");
        assertThat(active.activeCredential().status()).isEqualTo(OpenRouterCredentialStatus.ACTIVE);
        assertThat(active.rotationCredential()).isNull();
        assertThat(active.eligibleForBinding()).isTrue();
        assertThat(objectMapper.writeValueAsString(active))
                .doesNotContain("identity-runtime-secret")
                .doesNotContain("vendorIdentityKey");
        assertThat(selectionService.select(orgId, BigDecimal.ONE, null).getPublicId())
                .isEqualTo(created.id());
    }

    @Test
    void stageRejectsAnotherWorkspaceFromAnAlreadyRegisteredBillingAccount() {
        UUID existingWorkspace = UUID.fromString("10000000-0000-4000-8000-000000000002");
        insertActiveAccount("기존 사업", existingWorkspace, "existing-management-key");
        OpenRouterAccountResponse candidate = create("새 사업");
        when(client.createKey(eq("existing-management-key"), eq(existingWorkspace),
                anyString(), eq(BigDecimal.ZERO), isNull(), any(Instant.class)))
                .thenReturn(new OpenRouterClient.CreatedKey(
                        "billing-probe", "billing-runtime", existingWorkspace));
        when(client.getKey(MANAGEMENT_KEY, existingWorkspace, "billing-probe"))
                .thenReturn(new OpenRouterClient.ManagedKey(
                        "billing-probe", "probe", false, BigDecimal.ZERO,
                        null, true, BigDecimal.ZERO, existingWorkspace));

        assertThatThrownBy(() -> service.stage(sysAdmin, candidate.id(),
                new StageOpenRouterCredentialRequest(MANAGEMENT_KEY, "새 사업"),
                "127.0.0.1")).isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(409);
                    assertThat(error.getCode()).isEqualTo(
                            "OPENROUTER_CREDENTIAL_INVALID_STATE");
                });
        assertThat(credentialRepository.findByAccountIdOrderByIdAsc(
                accountRepository.findByPublicId(candidate.id()).orElseThrow().getId()))
                .isEmpty();
    }

    @Test
    void billingIdentityReservationSurvivesDeletingTheLastCredential() {
        UUID existingWorkspace = UUID.fromString("10000000-0000-4000-8000-000000000003");
        OpenRouterAccount existing = insertActiveAccount(
                "예약 사업", existingWorkspace, "existing-management-key");
        existing.establishVendorIdentityKey("reserved-identity-hash", Instant.now());
        accountRepository.saveAndFlush(existing);
        credentialRepository.deleteAll(
                credentialRepository.findByAccountIdOrderByIdAsc(existing.getId()));
        credentialRepository.flush();
        OpenRouterAccountResponse candidate = create("재등록 사업");
        when(client.getKey(MANAGEMENT_KEY, existingWorkspace, "reserved-identity-hash"))
                .thenReturn(new OpenRouterClient.ManagedKey(
                        "reserved-identity-hash", "identity", true, BigDecimal.ZERO,
                        null, true, BigDecimal.ZERO, existingWorkspace));

        assertThatThrownBy(() -> service.stage(sysAdmin, candidate.id(),
                new StageOpenRouterCredentialRequest(MANAGEMENT_KEY, "재등록 사업"),
                "127.0.0.1")).isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(409);
                    assertThat(error.getCode()).isEqualTo(
                            "OPENROUTER_CREDENTIAL_INVALID_STATE");
                });
    }

    @Test
    void preCreditsAccountCannotDeleteItsLastCredentialBeforeIdentityRotation() {
        OpenRouterAccount existing = insertActiveAccount(
                "이전 account", VENDOR_WORKSPACE, "existing-management-key");
        when(client.credits("existing-management-key"))
                .thenThrow(new OpenRouterException(401, "revoked vendor credential"));

        assertThatThrownBy(() -> service.deleteActive(sysAdmin, existing.getPublicId(),
                new FinalizeOpenRouterCredentialRequest("이전 account", true), "127.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(409);
                    assertThat(error.getCode()).isEqualTo(
                            "OPENROUTER_CREDENTIAL_INVALID_STATE");
                    assertThat(error.getDetail()).contains("identity marker");
                });
        assertThat(credentialRepository.findByAccountIdAndStatus(existing.getId(),
                OpenRouterCredentialStatus.ACTIVE)).isPresent();
    }

    /**
     * The invariants the schema keeps on its own, after the global source was
     * retired: a key that can spend money always names the account funding
     * it, and that naming never moves.
     *
     * <p>The approval case is the one worth spelling out. The trigger body
     * reads the money grant before it reads anything else, so an approval
     * that names an account commits on a path that never touches the rule
     * below — which makes it easy to test the wrong half and conclude the
     * rule holds.</p>
     */
    @Test
    void databaseRejectsPositiveUnboundCrossOrgAndRebindingWrites() {
        OpenRouterAccount account = accountRepository.findByPublicId(create("사업 A").id()).orElseThrow();
        long workspaceId = workspace(orgId, "바인딩 시험");
        long requestId = request(orgId, workspaceId, "바인딩 시험");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, credit_limit, created_by)
                values (?, ?, ?, 'unbound', 1, ?)
                """, workspaceId, orgId, requestId, sysAdmin.id()))
                .isInstanceOf(DataIntegrityViolationException.class);

        long zeroKey = jdbcTemplate.queryForObject("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, credit_limit, created_by)
                values (?, ?, ?, 'zero', 0, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, sysAdmin.id());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update llm_api_keys set credit_limit = 2 where id = ?", zeroKey))
                .isInstanceOf(DataIntegrityViolationException.class);

        long unboundApproval = request(orgId, workspaceId, "unbound-approval");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into llm_key_request_details
                       (request_id, granted_credit_limit)
                values (?, 3)
                """, unboundApproval)).isInstanceOf(DataAccessException.class);

        long boundApproval = request(orgId, workspaceId, "bound-approval");
        jdbcTemplate.update("""
                insert into llm_key_request_details
                       (request_id, granted_credit_limit, granted_openrouter_account_id)
                values (?, 3, ?)
                """, boundApproval, account.getId());
        assertThat(jdbcTemplate.queryForObject("""
                select granted_openrouter_account_id
                  from llm_key_request_details where request_id = ?
                """, Long.class, boundApproval)).isEqualTo(account.getId());

        long keyId = jdbcTemplate.queryForObject("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, credit_limit,
                        openrouter_account_id, created_by)
                values (?, ?, ?, 'bound', 1, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, account.getId(), sysAdmin.id());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update llm_api_keys set openrouter_account_id = null where id = ?", keyId))
                .isInstanceOf(DataAccessException.class);

        OpenRouterAccount second = insertActiveAccount("사업 B",
                UUID.fromString("10000000-0000-4000-8000-000000000002"), "second-key");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update llm_api_keys set openrouter_account_id = ? where id = ?",
                second.getId(), keyId)).isInstanceOf(DataAccessException.class);

        long unboundZero = jdbcTemplate.queryForObject("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, credit_limit, created_by)
                values (?, ?, ?, 'to-bind', 0, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, sysAdmin.id());
        jdbcTemplate.update(
                "update llm_api_keys set openrouter_account_id = ? where id = ?",
                account.getId(), unboundZero);
        assertThat(jdbcTemplate.queryForObject(
                "select openrouter_account_id from llm_api_keys where id = ?",
                Long.class, unboundZero)).isEqualTo(account.getId());

        long provisionedUnbound = jdbcTemplate.queryForObject("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, credit_limit,
                        openrouter_key_hash, openrouter_key_enc, created_by)
                values (?, ?, ?, 'remote-unbound', 0, 'stranded-remote-hash',
                        'stranded-runtime-ciphertext', ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, sysAdmin.id());
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update llm_api_keys set openrouter_account_id = ? where id = ?",
                account.getId(), provisionedUnbound)).isInstanceOf(DataAccessException.class);

        long otherOrgId = jdbcTemplate.queryForObject(
                "insert into orgs (name) values ('다른 기관') returning id", Long.class);
        long otherWorkspace = workspace(otherOrgId, "다른 기관 공간");
        long otherRequest = request(otherOrgId, otherWorkspace, "다른 기관 신청");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, credit_limit,
                        openrouter_account_id, created_by)
                values (?, ?, ?, 'cross-org', 1, ?, ?)
                """, otherWorkspace, otherOrgId, otherRequest, account.getId(), sysAdmin.id()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into llm_key_request_details
                       (request_id, granted_credit_limit, granted_openrouter_account_id)
                values (?, 1, ?)
                """, otherRequest, account.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void duplicateWorkspaceAndDuplicatePatchedNameAreRejected() {
        OpenRouterAccountResponse first = create("사업 A");
        service.stage(sysAdmin, first.id(),
                new StageOpenRouterCredentialRequest(MANAGEMENT_KEY, "사업 A"), "127.0.0.1");
        OpenRouterAccountResponse second = create("사업 B");
        assertThatThrownBy(() -> service.stage(sysAdmin, second.id(),
                new StageOpenRouterCredentialRequest("second-management-key", "사업 B"),
                "127.0.0.1")).isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(409));

        UpdateOpenRouterAccountRequest rename = new UpdateOpenRouterAccountRequest();
        rename.setName("사업 a");
        assertThatThrownBy(() -> service.update(sysAdmin, second.id(), rename, "127.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(409));
    }

    @Test
    void activationVerificationAttemptRecordsFailureThenRefreshesOnSuccess() {
        OpenRouterAccountResponse account = create("검증 기록 사업");
        OpenRouterAccountResponse staged = service.stage(sysAdmin, account.id(),
                new StageOpenRouterCredentialRequest(MANAGEMENT_KEY, "검증 기록 사업"),
                "127.0.0.1");
        Instant firstSuccess = staged.rotationCredential().verifiedAt();

        when(client.credits(MANAGEMENT_KEY))
                .thenThrow(new OpenRouterException(429, "vendor body must not persist"));
        assertThatThrownBy(() -> service.activate(sysAdmin, account.id(),
                new ConfirmOpenRouterAccountRequest("검증 기록 사업"), "127.0.0.1"))
                .isInstanceOf(ApiException.class);

        OpenRouterAccountResponse failed = service.get(sysAdmin, account.id());
        assertThat(failed.rotationCredential().verificationError())
                .isEqualTo(OpenRouterCredentialError.THROTTLED);
        assertThat(failed.rotationCredential().lastVerificationAttemptAt())
                .isAfterOrEqualTo(firstSuccess);
        assertThat(failed.rotationCredential().verifiedAt())
                .isCloseTo(firstSuccess, within(1, ChronoUnit.MICROS));

        doReturn(new OpenRouterClient.Credits(BigDecimal.TEN, BigDecimal.ZERO))
                .when(client).credits(MANAGEMENT_KEY);
        OpenRouterAccountResponse active = service.activate(sysAdmin, account.id(),
                new ConfirmOpenRouterAccountRequest("검증 기록 사업"), "127.0.0.1");
        assertThat(active.activeCredential().verificationError()).isNull();
        assertThat(active.activeCredential().verifiedAt())
                .isAfterOrEqualTo(failed.rotationCredential().lastVerificationAttemptAt());
        assertThat(active.activeCredential().lastVerificationAttemptAt())
                .isEqualTo(active.activeCredential().verifiedAt());
    }

    @Test
    void rollbackVerificationPersistsFailureAndThenSuccessMetadata() {
        OpenRouterAccountResponse account = create("rollback 검증 사업");
        service.stage(sysAdmin, account.id(),
                new StageOpenRouterCredentialRequest(MANAGEMENT_KEY, "rollback 검증 사업"),
                "127.0.0.1");
        service.activate(sysAdmin, account.id(),
                new ConfirmOpenRouterAccountRequest("rollback 검증 사업"), "127.0.0.1");
        service.stage(sysAdmin, account.id(),
                new StageOpenRouterCredentialRequest("new-management-key", "rollback 검증 사업"),
                "127.0.0.1");
        OpenRouterAccountResponse rotated = service.activate(sysAdmin, account.id(),
                new ConfirmOpenRouterAccountRequest("rollback 검증 사업"), "127.0.0.1");
        Instant priorSuccess = rotated.rotationCredential().verifiedAt();

        when(client.credits(MANAGEMENT_KEY))
                .thenThrow(new OpenRouterException(429, "vendor body must not persist"));
        assertThatThrownBy(() -> service.rollback(sysAdmin, account.id(),
                new ConfirmOpenRouterAccountRequest("rollback 검증 사업"), "127.0.0.1"))
                .isInstanceOf(ApiException.class);

        OpenRouterAccountResponse failed = service.get(sysAdmin, account.id());
        assertThat(failed.rotationCredential().status())
                .isEqualTo(OpenRouterCredentialStatus.RETIRING);
        assertThat(failed.rotationCredential().verificationError())
                .isEqualTo(OpenRouterCredentialError.THROTTLED);
        assertThat(failed.rotationCredential().lastVerificationAttemptAt())
                .isAfterOrEqualTo(priorSuccess);
        assertThat(failed.rotationCredential().verifiedAt()).isEqualTo(priorSuccess);

        doReturn(new OpenRouterClient.Credits(BigDecimal.TEN, BigDecimal.ZERO))
                .when(client).credits(MANAGEMENT_KEY);
        OpenRouterAccountResponse rolledBack = service.rollback(sysAdmin, account.id(),
                new ConfirmOpenRouterAccountRequest("rollback 검증 사업"), "127.0.0.1");
        assertThat(rolledBack.activeCredential().verificationError()).isNull();
        assertThat(rolledBack.activeCredential().verifiedAt())
                .isAfterOrEqualTo(failed.rotationCredential().lastVerificationAttemptAt());
        assertThat(rolledBack.activeCredential().lastVerificationAttemptAt())
                .isEqualTo(rolledBack.activeCredential().verifiedAt());
    }

    @Test
    void activeVerificationErrorBlocksNewBindingUntilReconcileSucceeds() {
        OpenRouterAccount account = insertActiveAccount(
                "credential 오류 사업", VENDOR_WORKSPACE, MANAGEMENT_KEY);
        long workspaceId = workspace(orgId, "credential-error");
        UUID boundKeyId = insertKey(workspaceId,
                request(orgId, workspaceId, "credential-error-key"),
                BigDecimal.ONE, account.getId());
        jdbcTemplate.update("""
                update openrouter_account_credentials
                   set verification_error = 'CREDENTIAL_ERROR'::openrouter_credential_error
                 where account_id = ?
                   and status = 'ACTIVE'::openrouter_credential_status
                """, account.getId());

        OpenRouterAccountResponse failed = service.get(sysAdmin, account.getPublicId());
        assertThat(failed.credentialAvailable()).isFalse();
        assertThat(failed.eligibleForBinding()).isFalse();
        assertThatThrownBy(() -> selectionService.select(
                orgId, BigDecimal.ONE, account.getPublicId())).isInstanceOf(ApiException.class);

        OpenRouterManagementAccess existingAccess = credentialResolver.forKey(
                keyRepository.findByPublicId(boundKeyId).orElseThrow()).orElseThrow();
        credentialResolver.markReconciled(existingAccess, Instant.now());

        OpenRouterAccountResponse recovered = service.get(sysAdmin, account.getPublicId());
        assertThat(recovered.credentialAvailable()).isTrue();
        assertThat(recovered.eligibleForBinding()).isTrue();
        assertThat(recovered.activeCredential().verificationError()).isNull();
    }

    @Test
    void olderReconcileSuccessCannotClearANewerVerificationFailure() {
        OpenRouterAccount account = insertActiveAccount(
                "newer failure 사업", VENDOR_WORKSPACE, MANAGEMENT_KEY);
        OpenRouterManagementAccess access = accessForBoundKey(account, "newer-failure");
        Instant olderSuccess = Instant.parse("2030-01-01T00:00:00Z");
        Instant newerFailure = Instant.parse("2030-01-01T00:01:00Z");

        credentialResolver.markVerificationFailure(
                access, OpenRouterCredentialError.CREDENTIAL_ERROR, newerFailure);
        credentialResolver.markReconciled(access, olderSuccess);

        OpenRouterAccountCredential credential = credentialRepository
                .findById(access.credentialId()).orElseThrow();
        assertThat(credential.getLastVerificationAttemptAt()).isEqualTo(newerFailure);
        assertThat(credential.getVerificationError())
                .isEqualTo(OpenRouterCredentialError.CREDENTIAL_ERROR);
        assertThat(credential.getLastUsedAt()).isEqualTo(olderSuccess);
        assertThat(credential.getLastReconciledAt()).isEqualTo(olderSuccess);
        assertThat(credential.getVerifiedAt()).isEqualTo(olderSuccess);
    }

    @Test
    void olderVerificationFailureCannotReplaceANewerReconcileSuccess() {
        OpenRouterAccount account = insertActiveAccount(
                "newer success 사업", VENDOR_WORKSPACE, MANAGEMENT_KEY);
        OpenRouterManagementAccess access = accessForBoundKey(account, "newer-success");
        Instant olderFailure = Instant.parse("2030-01-02T00:00:00Z");
        Instant newerSuccess = Instant.parse("2030-01-02T00:01:00Z");

        credentialResolver.markReconciled(access, newerSuccess);
        credentialResolver.markVerificationFailure(
                access, OpenRouterCredentialError.VENDOR_REJECTED, olderFailure);

        OpenRouterAccountCredential credential = credentialRepository
                .findById(access.credentialId()).orElseThrow();
        assertThat(credential.getLastVerificationAttemptAt()).isEqualTo(newerSuccess);
        assertThat(credential.getVerificationError()).isNull();
        assertThat(credential.getVerifiedAt()).isEqualTo(newerSuccess);
    }

    @Test
    void timestampTouchesCannotOverwriteConcurrentCredentialRotation() throws Exception {
        OpenRouterAccount account = insertActiveAccount(
                "timestamp race 사업", VENDOR_WORKSPACE, MANAGEMENT_KEY);
        long workspaceId = workspace(orgId, "timestamp-race");
        UUID boundKeyId = insertKey(workspaceId,
                request(orgId, workspaceId, "timestamp-race-key"),
                BigDecimal.ONE, account.getId());
        OpenRouterManagementAccess oldAccess = credentialResolver.forKey(
                keyRepository.findByPublicId(boundKeyId).orElseThrow()).orElseThrow();
        credentialResolver.markReconciled(oldAccess, Instant.now());
        service.stage(sysAdmin, account.getPublicId(),
                new StageOpenRouterCredentialRequest("new-management-key", "timestamp race 사업"),
                "127.0.0.1");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Instant> finalTouch = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var touches = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test timestamp start timed out");
                }
                for (int i = 0; i < 50; i++) {
                    Instant when = Instant.now();
                    credentialResolver.markUsed(oldAccess, when);
                    credentialResolver.markReconciled(oldAccess, when);
                    finalTouch.set(when);
                }
                return null;
            });
            var rotation = executor.submit(() -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test rotation start timed out");
                }
                return service.activate(sysAdmin, account.getPublicId(),
                        new ConfirmOpenRouterAccountRequest("timestamp race 사업"), "127.0.0.1");
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            touches.get(10, TimeUnit.SECONDS);
            rotation.get(10, TimeUnit.SECONDS);

            OpenRouterAccountCredential oldCredential = credentialRepository
                    .findById(oldAccess.credentialId()).orElseThrow();
            assertThat(oldCredential.getStatus()).isEqualTo(OpenRouterCredentialStatus.RETIRING);
            assertThat(oldCredential.getLastUsedAt()).isAfterOrEqualTo(
                    finalTouch.get().minus(1, ChronoUnit.MICROS));
            assertThat(oldCredential.getLastReconciledAt()).isNotNull();
            OpenRouterAccountResponse rotated = service.get(sysAdmin, account.getPublicId());
            assertThat(rotated.activeCredential().status())
                    .isEqualTo(OpenRouterCredentialStatus.ACTIVE);
            assertThat(rotated.rotationCredential().status())
                    .isEqualTo(OpenRouterCredentialStatus.RETIRING);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void replacementRollbackFinalizeAndDeleteGuardsPreserveCiphertextUntilSafe() {
        OpenRouterAccountResponse account = create("회전 사업");
        service.stage(sysAdmin, account.id(),
                new StageOpenRouterCredentialRequest(MANAGEMENT_KEY, "회전 사업"), "127.0.0.1");
        service.activate(sysAdmin, account.id(), new ConfirmOpenRouterAccountRequest("회전 사업"),
                "127.0.0.1");
        service.stage(sysAdmin, account.id(),
                new StageOpenRouterCredentialRequest("new-management-key", "회전 사업"),
                "127.0.0.1");
        OpenRouterAccountResponse rotated = service.activate(sysAdmin, account.id(),
                new ConfirmOpenRouterAccountRequest("회전 사업"), "127.0.0.1");
        assertThat(rotated.activeCredential()).isNotNull();
        assertThat(rotated.rotationCredential().status())
                .isEqualTo(OpenRouterCredentialStatus.RETIRING);
        verify(client).getKey("new-management-key", VENDOR_WORKSPACE, "probe-hash");
        verify(client).updateLimit("new-management-key", VENDOR_WORKSPACE, "probe-hash",
                BigDecimal.ZERO, null);
        verify(client, atLeastOnce()).deleteKey(
                "new-management-key", VENDOR_WORKSPACE, "probe-hash");
        assertThatThrownBy(() -> service.finalizeRetiring(sysAdmin, account.id(),
                new FinalizeOpenRouterCredentialRequest("회전 사업", true), "127.0.0.1"))
                .isInstanceOf(ApiException.class);

        OpenRouterAccountResponse rolledBack = service.rollback(sysAdmin, account.id(),
                new ConfirmOpenRouterAccountRequest("회전 사업"), "127.0.0.1");
        assertThat(rolledBack.rotationCredential().status())
                .isEqualTo(OpenRouterCredentialStatus.STAGED);
        service.activate(sysAdmin, account.id(), new ConfirmOpenRouterAccountRequest("회전 사업"),
                "127.0.0.1");
        jdbcTemplate.update("""
                update openrouter_account_credentials
                   set last_reconciled_at = now()
                 where account_id = (select id from openrouter_accounts where public_id = ?)
                   and status = 'ACTIVE'::openrouter_credential_status
                """, account.id());
        Instant activeVerifiedBeforeFinalize = service.get(sysAdmin, account.id())
                .activeCredential().verifiedAt();
        when(client.credits(MANAGEMENT_KEY))
                .thenThrow(new OpenRouterException(401, "revoked vendor credential"));
        OpenRouterAccountResponse finalized = service.finalizeRetiring(sysAdmin, account.id(),
                new FinalizeOpenRouterCredentialRequest("회전 사업", true), "127.0.0.1");
        assertThat(finalized.rotationCredential()).isNull();
        assertThat(finalized.activeCredential().verifiedAt())
                .isAfterOrEqualTo(activeVerifiedBeforeFinalize);

        long internalAccount = accountRepository.findByPublicId(account.id()).orElseThrow().getId();
        long workspace = workspace(orgId, "삭제 guard");
        long request = request(orgId, workspace, "삭제 guard");
        jdbcTemplate.update("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, credit_limit,
                        openrouter_account_id, created_by)
                values (?, ?, ?, 'guard', 0, ?, ?)
                """, workspace, orgId, request, internalAccount, sysAdmin.id());
        assertThatThrownBy(() -> service.deleteActive(sysAdmin, account.id(),
                new FinalizeOpenRouterCredentialRequest("회전 사업", true), "127.0.0.1"))
                .isInstanceOf(ApiException.class);
        jdbcTemplate.update("delete from llm_api_keys where openrouter_account_id = ?",
                internalAccount);
        when(client.credits("new-management-key"))
                .thenThrow(new OpenRouterException(403, "revoked vendor credential"));
        OpenRouterAccountResponse deleted = service.deleteActive(sysAdmin, account.id(),
                new FinalizeOpenRouterCredentialRequest("회전 사업", true), "127.0.0.1");
        assertThat(deleted.activeCredential()).isNull();
        assertThat(deleted.credentialAvailable()).isFalse();
    }

    @Test
    void immutableLimitsAndOrgScopeReturnDefinedDenials() {
        OpenRouterAccount first = insertActiveAccount("사업 A", VENDOR_WORKSPACE, MANAGEMENT_KEY);
        OpenRouterAccount second = insertActiveAccount("사업 B",
                UUID.fromString("10000000-0000-4000-8000-000000000003"), "second-key");
        long workspace = workspace(orgId, "limits");
        long request = request(orgId, workspace, "limits");
        UUID boundKey = insertKey(workspace, request, BigDecimal.ONE, first.getId());
        AdminLlmKeyLimitsRequest move = limits(BigDecimal.ONE, second.getPublicId());
        assertThatThrownBy(() -> adminLlmKeyService.replaceLimits(
                sysAdmin, boundKey, move, "127.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.getCode()).isEqualTo(
                                "LLM_KEY_OPENROUTER_ACCOUNT_IMMUTABLE"));

        UUID firstBinding = insertKey(workspace, request(orgId, workspace, "first-binding"),
                BigDecimal.ZERO, null);
        assertThat(adminLlmKeyService.replaceLimits(sysAdmin, firstBinding,
                limits(BigDecimal.ONE, first.getPublicId()), "127.0.0.1")
                .openrouterAccountId()).isEqualTo(first.getPublicId());

        // An unbound key that already carries a vendor key was provisioned
        // under a scope this account cannot see, so it is a new key or
        // nothing. (An unbound key with money is not a state that exists any
        // more; the schema refuses it.)
        UUID strandedRemote = insertKey(workspace, request(orgId, workspace, "stranded-remote"),
                BigDecimal.ZERO, null);
        jdbcTemplate.update("""
                update llm_api_keys
                   set openrouter_key_hash = 'stranded-hash',
                       openrouter_key_enc = 'stranded-ciphertext'
                 where public_id = ?
                """, strandedRemote);
        assertThatThrownBy(() -> adminLlmKeyService.replaceLimits(sysAdmin, strandedRemote,
                limits(BigDecimal.ONE, first.getPublicId()), "127.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, error ->
                        assertThat(error.getCode()).isEqualTo(
                                "LLM_KEY_OPENROUTER_ACCOUNT_IMMUTABLE"));
        // Not naming an account is the same refusal, not a different one. It
        // is worth its own case because the two halves take different code
        // paths, and the one that omits the id would otherwise reach the
        // write and surface a constraint violation as a 500.
        assertThatThrownBy(() -> adminLlmKeyService.replaceLimits(sysAdmin, strandedRemote,
                limits(BigDecimal.ONE, null), "127.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class, error -> {
                    assertThat(error.getStatus().value()).isEqualTo(409);
                    assertThat(error.getCode()).isEqualTo(
                            "LLM_KEY_OPENROUTER_ACCOUNT_IMMUTABLE");
                });
        // A limit change that asks for no money still works on the same row.
        assertThat(adminLlmKeyService.replaceLimits(sysAdmin, strandedRemote,
                limits(BigDecimal.ZERO, null), "127.0.0.1").openrouterAccountId()).isNull();

        UUID unbound = insertKey(workspace, request(orgId, workspace, "manager"),
                BigDecimal.ZERO, null);
        AuthenticatedUser sysManager = new AuthenticatedUser(sysAdmin.id(), UUID.randomUUID(),
                "manager@test", UserRole.SYS_MANAGER, Map.of());
        assertThatThrownBy(() -> adminLlmKeyService.replaceLimits(sysManager, unbound,
                limits(BigDecimal.ONE, first.getPublicId()), "127.0.0.1"))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(403));

        long otherOrg = jdbcTemplate.queryForObject(
                "insert into orgs (name) values ('범위 밖 기관') returning id", Long.class);
        OpenRouterAccount outside = accountRepository.saveAndFlush(new OpenRouterAccount(
                otherOrg, "범위 밖", null, null, sysAdmin.id()));
        AuthenticatedUser orgViewer = new AuthenticatedUser(sysAdmin.id(), UUID.randomUUID(),
                "viewer@test", UserRole.ORG_VIEWER, Map.of(orgId, UserRole.ORG_VIEWER));
        assertThatThrownBy(() -> service.get(orgViewer, outside.getPublicId()))
                .isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(404));
        UpdateOpenRouterAccountRequest update = new UpdateOpenRouterAccountRequest();
        update.setName("변경 거부");
        assertThatThrownBy(() -> service.update(orgViewer, outside.getPublicId(), update,
                "127.0.0.1")).isInstanceOfSatisfying(ApiException.class,
                        error -> assertThat(error.getStatus().value()).isEqualTo(404));
    }

    @Test
    void identicalStagedAndActiveSecretsAreRejectedAndRecorded() {
        OpenRouterAccountResponse account = create("동일 secret 사업");
        service.stage(sysAdmin, account.id(),
                new StageOpenRouterCredentialRequest(MANAGEMENT_KEY, "동일 secret 사업"),
                "127.0.0.1");
        service.activate(sysAdmin, account.id(),
                new ConfirmOpenRouterAccountRequest("동일 secret 사업"), "127.0.0.1");
        service.stage(sysAdmin, account.id(),
                new StageOpenRouterCredentialRequest(MANAGEMENT_KEY, "동일 secret 사업"),
                "127.0.0.1");

        assertThatThrownBy(() -> service.activate(sysAdmin, account.id(),
                new ConfirmOpenRouterAccountRequest("동일 secret 사업"), "127.0.0.1"))
                .isInstanceOf(ApiException.class);
        assertThat(service.get(sysAdmin, account.id()).rotationCredential().verificationError())
                .isEqualTo(OpenRouterCredentialError.CREDENTIAL_ERROR);
    }

    @Test
    void explicitBindingSelectionLockSerializesAccountArchive() throws Exception {
        assertBindingSelectionLock(false);
    }

    @Test
    void automaticBindingSelectionLockSerializesAccountArchive() throws Exception {
        assertBindingSelectionLock(true);
    }

    // Approval is the only path by which a key is ever created, so the line that
    // puts the fence on the key row is the line the whole feature rests on. The
    // request detail, the audit and every screen read the reviewer's decision
    // from elsewhere, and all of them stay correct if that one line is lost —
    // only the row the gateway serves goes quietly back to unrestricted.
    @Test
    void approvalStoresTheGrantedFenceOnTheKeyTheGatewayServes() {
        OpenRouterAccount account = insertActiveAccount(
                "fence 사업", UUID.randomUUID(), "sk-or-v1-fence");
        long workspaceId = workspace(orgId, "fence");
        long requestId = request(orgId, workspaceId, "approval-fence");
        jdbcTemplate.update("insert into llm_key_request_details (request_id) values (?)",
                requestId);
        Request approvalRequest = requestRepository.findById(requestId).orElseThrow();

        // Inside a transaction, like the approval endpoint: the request detail is
        // an existing row, so its grant lands by dirty checking at flush.
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                requestSupport.materialize(approvalRequest, new ApproveRequestRequest(
                        null, null, null, null,
                        new ApproveLlmKeyRequestSpec(null, null, null, null, BigDecimal.ONE,
                                null, java.util.List.of("OpenAI/*", " anthropic/claude-sonnet-4 "),
                                account.getPublicId())), sysAdmin));

        // The row the sync document is built from — not the request detail.
        String onKey = jdbcTemplate.queryForObject(
                "select credit_allowed_models::text from llm_api_keys where request_id = ?",
                String.class, requestId);
        assertThat(onKey).isEqualTo("[\"openai/*\", \"anthropic/claude-sonnet-4\"]");

        // And the approval history, which is what the screens read back.
        String onDetail = jdbcTemplate.queryForObject(
                "select granted_credit_allowed_models::text from llm_key_request_details "
                        + "where request_id = ?", String.class, requestId);
        assertThat(onDetail).isEqualTo("[\"openai/*\", \"anthropic/claude-sonnet-4\"]");
    }

    @Test
    void approvalAndFirstLimitsBindingShareGenerationThenAccountLockOrder() throws Exception {
        OpenRouterAccount account = insertActiveAccount(
                "lock-order 사업", VENDOR_WORKSPACE, MANAGEMENT_KEY);
        long workspaceId = workspace(orgId, "lock-order");
        long approvalRequestId = request(orgId, workspaceId, "approval-lock-order");
        jdbcTemplate.update("insert into llm_key_request_details (request_id) values (?)",
                approvalRequestId);
        Request approvalRequest = requestRepository.findById(approvalRequestId).orElseThrow();
        ApproveRequestRequest approvalForm = new ApproveRequestRequest(
                null, null, null, null,
                new ApproveLlmKeyRequestSpec(null, null, null, null, BigDecimal.ONE,
                        null, null, account.getPublicId()));
        UUID firstBinding = insertKey(workspaceId,
                request(orgId, workspaceId, "limits-lock-order"), BigDecimal.ZERO, null);

        CountDownLatch approvalSelected = new CountDownLatch(1);
        CountDownLatch releaseApproval = new CountDownLatch(1);
        AtomicReference<Thread> approvalThread = new AtomicReference<>();
        doAnswer(invocation -> {
            Object selected = invocation.callRealMethod();
            if (Thread.currentThread() == approvalThread.get()) {
                approvalSelected.countDown();
                if (!releaseApproval.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test approval release timed out");
                }
            }
            return selected;
        }).when(selectionService).select(orgId, BigDecimal.ONE, account.getPublicId());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var approval = executor.submit(() -> {
                approvalThread.set(Thread.currentThread());
                return new TransactionTemplate(transactionManager).execute(status ->
                        requestSupport.materialize(approvalRequest, approvalForm, sysAdmin));
            });
            assertThat(approvalSelected.await(5, TimeUnit.SECONDS)).isTrue();

            var binding = executor.submit(() -> adminLlmKeyService.replaceLimits(
                    sysAdmin, firstBinding,
                    limits(BigDecimal.ONE, account.getPublicId()), "127.0.0.1"));
            Thread.sleep(150);
            assertThat(binding.isDone()).isFalse();

            releaseApproval.countDown();
            approval.get(5, TimeUnit.SECONDS);
            assertThat(binding.get(5, TimeUnit.SECONDS).openrouterAccountId())
                    .isEqualTo(account.getPublicId());
        } finally {
            approvalThread.set(null);
            releaseApproval.countDown();
            executor.shutdownNow();
        }
    }

    private void assertBindingSelectionLock(boolean automatic) throws Exception {
        OpenRouterAccount account = insertActiveAccount(
                automatic ? "자동 직렬화 사업" : "명시 직렬화 사업", VENDOR_WORKSPACE,
                MANAGEMENT_KEY);
        CountDownLatch selected = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var selection = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .execute(status -> {
                        selectionService.select(orgId, BigDecimal.ONE,
                                automatic ? null : account.getPublicId());
                        selected.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test lock release timed out");
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        return null;
                    }));
            assertThat(selected.await(5, TimeUnit.SECONDS)).isTrue();

            UpdateOpenRouterAccountRequest archive = new UpdateOpenRouterAccountRequest();
            archive.setStatus(OpenRouterAccountStatus.ARCHIVED);
            var update = executor.submit(() -> service.update(sysAdmin, account.getPublicId(),
                    archive, "127.0.0.1"));
            Thread.sleep(150);
            assertThat(update.isDone()).isFalse();

            release.countDown();
            selection.get(5, TimeUnit.SECONDS);
            assertThat(update.get(5, TimeUnit.SECONDS).status())
                    .isEqualTo(OpenRouterAccountStatus.ARCHIVED);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    // The default is a prefill source for the approval form, so it must round
    // trip through create, patch and read — and it must NOT bump the gateway
    // generation, because it reaches no issued key and appears in no document.
    // Every other LLM write here bumps, which is why this is pinned: a later
    // round that "fixes" the missing bump would be turning a copy source into a
    // runtime inheritance root, and every already-issued key's spending
    // permission would start moving when an administrator edits a form.
    @Test
    void accountDefaultModelListRoundTripsWithoutTouchingTheGateway() {
        jdbcTemplate.update("insert into llm_gateway_state (id, generation, service_enabled) "
                + "values (true, 1, true) on conflict (id) do update set generation = 1");
        OpenRouterAccountResponse created = create("기본 목록 account");
        assertThat(created.defaultCreditAllowedModels()).isEmpty();

        UpdateOpenRouterAccountRequest form = new UpdateOpenRouterAccountRequest();
        form.setDefaultCreditAllowedModels(java.util.List.of("OpenAI/*", " openai/* "));
        OpenRouterAccountResponse updated =
                service.update(sysAdmin, created.id(), form, "127.0.0.1");
        // Normalized the same way the key's own list is: lower-cased, deduped.
        assertThat(updated.defaultCreditAllowedModels()).containsExactly("openai/*");
        assertThat(service.get(sysAdmin, created.id()).defaultCreditAllowedModels())
                .containsExactly("openai/*");

        Long generation = jdbcTemplate.queryForObject(
                "select generation from llm_gateway_state where id", Long.class);
        assertThat(generation).isEqualTo(1L);

        // An empty list clears it; the form opens blank from the next approval.
        UpdateOpenRouterAccountRequest cleared = new UpdateOpenRouterAccountRequest();
        cleared.setDefaultCreditAllowedModels(java.util.List.of());
        assertThat(service.update(sysAdmin, created.id(), cleared, "127.0.0.1")
                .defaultCreditAllowedModels()).isEmpty();
    }

    private OpenRouterAccountResponse create(String name) {
        return service.create(sysAdmin, new CreateOpenRouterAccountRequest(
<<<<<<< HEAD
                orgPublicId, name, "사업 코드", null, name), "127.0.0.1");
=======
                orgPublicId, name, "재원", null, null, name), "127.0.0.1");
>>>>>>> 55335e4 (feat: grant a per-key commercial model allow list)
    }

    private OpenRouterManagementAccess accessForBoundKey(
            OpenRouterAccount account, String name) {
        long workspaceId = workspace(orgId, name);
        UUID keyId = insertKey(workspaceId, request(orgId, workspaceId, name),
                BigDecimal.ONE, account.getId());
        return credentialResolver.forKey(
                keyRepository.findByPublicId(keyId).orElseThrow()).orElseThrow();
    }

    private OpenRouterAccount insertActiveAccount(String name, UUID workspaceId, String secret) {
        OpenRouterAccount account = new OpenRouterAccount(orgId, name, null, null,
                sysAdmin.id());
        account.discoverVendorWorkspace(workspaceId, Instant.now());
        account = accountRepository.saveAndFlush(account);
        OpenRouterAccountCredential credential = new OpenRouterAccountCredential(account.getId(),
                managementCipher.encrypt(account.getPublicId(), secret), sysAdmin.id(), Instant.now());
        credential.activate(Instant.now());
        credentialRepository.saveAndFlush(credential);
        return account;
    }

    private UUID insertKey(long workspaceId, long requestId, BigDecimal credit,
            Long accountId) {
        long id = jdbcTemplate.queryForObject("""
                insert into llm_api_keys
                       (workspace_id, org_id, request_id, name, credit_limit,
                        openrouter_account_id, created_by)
                values (?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, workspaceId, orgId, requestId, "key-" + UUID.randomUUID(),
                credit, accountId, sysAdmin.id());
        return jdbcTemplate.queryForObject(
                "select public_id from llm_api_keys where id = ?", UUID.class, id);
    }

    private AdminLlmKeyLimitsRequest limits(BigDecimal credit, UUID accountId) {
        AdminLlmKeyLimitsRequest request = new AdminLlmKeyLimitsRequest();
        request.setRpm(60);
        request.setTpm(1000);
        request.setConcurrency(4);
        request.setDailyTokens(10000L);
        request.setCreditLimit(credit);
        request.setCreditLimitReset(null);
        request.setCreditAllowedModels(null);
        request.setOpenrouterAccountId(accountId);
        return request;
    }

    private long workspace(long ownerOrgId, String name) {
        return jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM'::workspace_kind, ?) returning id",
                Long.class, name + UUID.randomUUID());
    }

    private long request(long ownerOrgId, long workspaceId, String name) {
        return jdbcTemplate.queryForObject("""
                insert into requests
                       (resource_type, workspace_id, org_id, requester_id, purpose, display_name)
                values ('LLM_API_KEY', ?, ?, ?, '시험', ?)
                returning id
                """, Long.class, workspaceId, ownerOrgId, sysAdmin.id(), name + UUID.randomUUID());
    }
}
