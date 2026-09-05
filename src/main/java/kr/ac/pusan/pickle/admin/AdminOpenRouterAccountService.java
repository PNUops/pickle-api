package kr.ac.pusan.pickle.admin;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.pusan.pickle.admin.dto.ConfirmOpenRouterAccountRequest;
import kr.ac.pusan.pickle.admin.dto.CreateOpenRouterAccountRequest;
import kr.ac.pusan.pickle.admin.dto.FinalizeOpenRouterCredentialRequest;
import kr.ac.pusan.pickle.admin.dto.OpenRouterAccountAllocationResponse;
import kr.ac.pusan.pickle.admin.dto.OpenRouterAccountResponse;
import kr.ac.pusan.pickle.admin.dto.OpenRouterCredentialStateResponse;
import kr.ac.pusan.pickle.admin.dto.StageOpenRouterCredentialRequest;
import kr.ac.pusan.pickle.admin.dto.UpdateOpenRouterAccountRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccount;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountCredential;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountCredentialRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountStatus;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountSelectionService;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAllocationQuery;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterClient;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCreditRefreshScheduler;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCredentialError;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCredentialStatus;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterException;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterManagementCredentialCipher;
import kr.ac.pusan.pickle.orgs.AdminOrgScope;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Institution-scoped OpenRouter account metadata and management-key rotation. */
@Service
public class AdminOpenRouterAccountService {

    private static final Duration RETIRING_WARNING = Duration.ofHours(24);

    private final OpenRouterAccountRepository accountRepository;
    private final OpenRouterAccountCredentialRepository credentialRepository;
    private final LlmApiKeyRepository keyRepository;
    private final OrgRepository orgRepository;
    private final OpenRouterClient client;
    private final OpenRouterManagementCredentialCipher credentialCipher;
    private final AuditService auditService;
    private final EntityManager entityManager;
    private final TransactionTemplate tx;
    private final OpenRouterAccountSelectionService accountSelection;
    private final OpenRouterAccountCreditsQueryService creditsQuery;
    private final OpenRouterAllocationQuery allocationQuery;
    private final OpenRouterCreditRefreshScheduler creditRefreshScheduler;
    private final ObjectMapper objectMapper;

    public AdminOpenRouterAccountService(OpenRouterAccountRepository accountRepository,
            OpenRouterAccountCredentialRepository credentialRepository,
            LlmApiKeyRepository keyRepository, OrgRepository orgRepository,
            OpenRouterClient client,
            OpenRouterManagementCredentialCipher credentialCipher, AuditService auditService,
            EntityManager entityManager, PlatformTransactionManager transactionManager,
            OpenRouterAccountSelectionService accountSelection,
            OpenRouterAccountCreditsQueryService creditsQuery,
            OpenRouterAllocationQuery allocationQuery,
            OpenRouterCreditRefreshScheduler creditRefreshScheduler, ObjectMapper objectMapper) {
        this.accountRepository = accountRepository;
        this.credentialRepository = credentialRepository;
        this.keyRepository = keyRepository;
        this.orgRepository = orgRepository;
        this.client = client;
        this.credentialCipher = credentialCipher;
        this.auditService = auditService;
        this.entityManager = entityManager;
        this.tx = new TransactionTemplate(transactionManager);
        this.accountSelection = accountSelection;
        this.creditsQuery = creditsQuery;
        this.allocationQuery = allocationQuery;
        this.creditRefreshScheduler = creditRefreshScheduler;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<OpenRouterAccountResponse> list(AuthenticatedUser actor, @Nullable UUID orgId) {
        Long requested = orgId == null ? null
                : orgRepository.findByPublicId(orgId).map(Org::getId).orElse(null);
        OrgScope scope = AdminOrgScope.read(actor, orgId, requested);
        if (!scope.isUnrestricted() && scope.orgIds().isEmpty()) {
            return List.of();
        }
        List<OpenRouterAccount> accounts = accountRepository.findAll().stream()
                .filter(account -> scope.isUnrestricted() || scope.orgIds().contains(account.getOrgId()))
                .sorted(Comparator.comparing(OpenRouterAccount::getName,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
        Map<Long, OpenRouterAllocationQuery.Allocation> allocations = allocationQuery.forAccounts(
                accounts.stream().map(OpenRouterAccount::getId).toList());
        return accounts.stream()
                .map(account -> response(account, allocations.get(account.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public OpenRouterAccountResponse get(AuthenticatedUser actor, UUID accountId) {
        return response(requireReadable(actor, accountId));
    }

    public OpenRouterAccountResponse create(AuthenticatedUser actor,
            CreateOpenRouterAccountRequest form, String ip) {
        Org org = requireWritableOrg(actor, form.orgId());
        confirm(form.name().strip(), form.confirmName());
        List<FieldValidationError> modelErrors = new ArrayList<>();
        List<String> defaultModels = CreditModelPatterns.normalize(
                form.defaultCreditAllowedModels(), "defaultCreditAllowedModels", modelErrors);
        if (!modelErrors.isEmpty()) {
            throw ApiException.validationFailed(modelErrors);
        }
        OpenRouterAccount account = new OpenRouterAccount(org.getId(), form.name().strip(),
                Texts.blankToNull(form.program()),
                Texts.blankToNull(form.contact()), actor.id());
        account.replaceDefaultCreditAllowedModels(
                CreditModelPatterns.toJson(objectMapper, defaultModels), Instant.now());
        try {
            return tx.execute(status -> {
                OpenRouterAccount saved = accountRepository.saveAndFlush(account);
                auditService.recordAfterCommit(actor.id(), actor.role().name(),
                        AuditService.OPENROUTER_ACCOUNT_CREATE, "openrouter_account",
                        saved.getPublicId(), Map.of("orgId", org.getPublicId()), ip);
                return response(saved);
            });
        } catch (DataIntegrityViolationException e) {
            throw invalidState("같은 기관에 동일한 이름 또는 vendor workspace의 account가 이미 있습니다.");
        }
    }

    @Transactional
    public OpenRouterAccountResponse update(AuthenticatedUser actor, UUID accountId,
            UpdateOpenRouterAccountRequest form, String ip) {
        OpenRouterAccount account = requireWritableWithLock(actor, accountId);
        if (!form.hasAny()) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("account",
                    "변경할 항목을 하나 이상 보내 주세요.")));
        }
        if (form.isNameSet() && (form.getName() == null || form.getName().isBlank())) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("name",
                    "Account 이름은 비울 수 없습니다.")));
        }
        if (form.isStatusSet() && form.getStatus() == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("status",
                    "Account 상태는 null일 수 없습니다.")));
        }
        OpenRouterAccountStatus nextStatus = form.isStatusSet()
                ? form.getStatus() : account.getStatus();
        if (nextStatus == OpenRouterAccountStatus.ARCHIVED
                && keyRepository.countUnsafeForAccountArchive(account.getId()) > 0) {
            throw invalidState("활성, 정지, 발급 대기 또는 미만료 key가 연결된 account는 보관할 수 없습니다.");
        }
        String nextName = form.isNameSet() ? form.getName().strip() : account.getName();
        String nextProgram = form.isProgramSet()
                ? Texts.blankToNull(form.getProgram()) : account.getProgram();
        String nextContact = form.isContactSet()
                ? Texts.blankToNull(form.getContact()) : account.getContact();
        if (form.isNameSet()) {
            accountRepository.findByOrgIdAndNameIgnoreCase(account.getOrgId(), nextName)
                    .filter(other -> !other.getId().equals(account.getId()))
                    .ifPresent(other -> { throw duplicateName(); });
        }
        Map<String, Object> auditArgs = new LinkedHashMap<>();
        if (form.isNameSet()) { auditArgs.put("name", nextName); }
        if (form.isProgramSet()) { auditArgs.put("program", nextProgram); }
        if (form.isContactSet()) { auditArgs.put("contact", nextContact); }
        if (form.isStatusSet()) { auditArgs.put("status", nextStatus.name()); }
        account.update(nextName, nextProgram, nextContact, nextStatus, Instant.now());
        // No generation bump here, and that is deliberate. This default is a
        // prefill source the approval form copies once; it reaches no issued key
        // and appears in no gateway document, so bumping would hand the gateway
        // a byte-identical document and report a change that did not happen.
        // Every other LLM write in this codebase bumps, which is exactly why
        // this exception is written down rather than left to be noticed.
        if (form.isDefaultCreditAllowedModelsSet()) {
            List<FieldValidationError> modelErrors = new ArrayList<>();
            List<String> nextModels = CreditModelPatterns.normalize(
                    form.getDefaultCreditAllowedModels(), "defaultCreditAllowedModels",
                    modelErrors);
            if (!modelErrors.isEmpty()) {
                throw ApiException.validationFailed(modelErrors);
            }
            auditArgs.put("defaultCreditAllowedModels", nextModels);
            account.replaceDefaultCreditAllowedModels(
                    CreditModelPatterns.toJson(objectMapper, nextModels), Instant.now());
        }
        try {
            accountRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            throw duplicateName();
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.OPENROUTER_ACCOUNT_UPDATE, "openrouter_account",
                account.getPublicId(), auditArgs, ip);
        return response(account);
    }

    public OpenRouterAccountResponse stage(AuthenticatedUser actor, UUID accountId,
            StageOpenRouterCredentialRequest form, String ip) {
        OpenRouterAccount snapshot = requireWritable(actor, accountId);
        confirm(snapshot.getName(), form.confirmName());
        requireActive(snapshot);
        if (!credentialCipher.configuredForWrite()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCodes.OPENROUTER_CREDENTIAL_KEYRING_UNAVAILABLE,
                    "Credential keyring을 사용할 수 없습니다",
                    "OpenRouter management credential 암호화 keyring을 먼저 구성해 주세요.");
        }
        UUID workspaceId = validateCandidate(snapshot, form.managementKey());
        rejectSharedVendorAccount(snapshot, form.managementKey());
        accountRepository.findByVendorWorkspaceId(workspaceId)
                .filter(other -> !other.getId().equals(snapshot.getId()))
                .ifPresent(other -> { throw workspaceConflict(); });
        try {
            return tx.execute(status -> {
                OpenRouterAccount locked = requireWritableWithLock(actor, accountId);
                requireActive(locked);
                if (credentialRepository.findByAccountIdOrderByIdAsc(locked.getId()).stream()
                        .anyMatch(c -> c.getStatus() != OpenRouterCredentialStatus.ACTIVE)) {
                    throw credentialState("이미 진행 중인 credential rotation이 있습니다.");
                }
                if (locked.getVendorWorkspaceId() == null) {
                    accountRepository.findByVendorWorkspaceId(workspaceId)
                            .filter(other -> !other.getId().equals(locked.getId()))
                            .ifPresent(other -> { throw workspaceConflict(); });
                    locked.discoverVendorWorkspace(workspaceId, Instant.now());
                } else if (!locked.getVendorWorkspaceId().equals(workspaceId)) {
                    throw verificationFailed(OpenRouterCredentialError.VENDOR_REJECTED);
                }
                String encrypted = credentialCipher.encrypt(locked.getPublicId(),
                        form.managementKey());
                credentialRepository.saveAndFlush(new OpenRouterAccountCredential(locked.getId(),
                        encrypted, actor.id(), Instant.now()));
                auditService.recordAfterCommit(actor.id(), actor.role().name(),
                        AuditService.OPENROUTER_CREDENTIAL_STAGE, "openrouter_account",
                        locked.getPublicId(), Map.of("verified", true), ip);
                return response(locked);
            });
        } catch (DataIntegrityViolationException e) {
            if (accountRepository.findByVendorWorkspaceId(workspaceId)
                    .filter(other -> !other.getId().equals(snapshot.getId())).isPresent()) {
                throw workspaceConflict();
            }
            throw credentialState("이미 진행 중인 credential rotation이 있습니다.");
        }
    }

    public OpenRouterAccountResponse activate(AuthenticatedUser actor, UUID accountId,
            ConfirmOpenRouterAccountRequest form, String ip) {
        CredentialSnapshot snapshot = credentialSnapshot(actor, accountId, form.confirmName());
        requireActive(snapshot.account());
        if (snapshot.staged() == null) {
            throw credentialState("활성화할 STAGED credential이 없습니다.");
        }
        OpenRouterAccountResponse result;
        AtomicReference<OpenRouterClient.CreatedKey> identityMarker = new AtomicReference<>();
        AtomicReference<String> identitySecret = new AtomicReference<>();
        try {
            result = tx.execute(status -> {
                // Serializes the only operation that can make a newly staged
                // vendor billing account ACTIVE. Without this, two concurrent
                // first activations can both observe no existing ACTIVE scope.
                Object identityLock = entityManager.createNativeQuery(
                        "select pg_try_advisory_xact_lock(6841807811705001)")
                        .getSingleResult();
                if (!Boolean.TRUE.equals(identityLock)) {
                    throw credentialState(
                            "다른 OpenRouter credential activation이 진행 중입니다. 다시 시도해 주세요.");
                }
                String stagedSecret = decrypt(snapshot.account(), snapshot.staged());
                String activeSecret = snapshot.active() == null ? null
                        : decrypt(snapshot.account(), snapshot.active());
                if (activeSecret != null && sameSecret(activeSecret, stagedSecret)) {
                    throw verificationFailed(OpenRouterCredentialError.CREDENTIAL_ERROR);
                }
                validateCandidate(snapshot.account(), stagedSecret);
                rejectSharedVendorAccount(snapshot.account(), stagedSecret);
                if (activeSecret != null) {
                    crossManagementProbe(snapshot.account(), activeSecret, stagedSecret);
                }

                OpenRouterAccount locked = requireWritableWithLock(actor, accountId);
                requireActive(locked);
                List<OpenRouterAccountCredential> credentials =
                        credentialRepository.findAllWithLockByAccountId(locked.getId());
                OpenRouterAccountCredential staged = byId(
                        credentials, snapshot.staged().getId());
                OpenRouterAccountCredential active = snapshot.active() == null ? null
                        : byId(credentials, snapshot.active().getId());
                if (staged.getStatus() != OpenRouterCredentialStatus.STAGED
                        || (active != null
                            && active.getStatus() != OpenRouterCredentialStatus.ACTIVE)) {
                    throw credentialState(
                            "검증 뒤 credential 상태가 바뀌었습니다. 다시 시도해 주세요.");
                }
                if (locked.getVendorIdentityKeyHash() == null) {
                    OpenRouterClient.CreatedKey marker = createIdentityMarker(
                            locked, stagedSecret);
                    identityMarker.set(marker);
                    identitySecret.set(stagedSecret);
                    locked.establishVendorIdentityKey(marker.hash(), Instant.now());
                }
                entityManager.createNativeQuery(
                        "set constraints openrouter_account_credentials_slot_uq deferred")
                        .executeUpdate();
                Instant now = Instant.now();
                if (active != null) {
                    active.markUsed(now);
                    active.retire(now);
                }
                staged.recordVerificationSuccess(now);
                staged.activate(now);
                auditService.recordAfterCommit(actor.id(), actor.role().name(),
                        AuditService.OPENROUTER_CREDENTIAL_ACTIVATE, "openrouter_account",
                        locked.getPublicId(), Map.of("rotation", active != null), ip);
                return response(locked);
            });
            identityMarker.set(null);
            identitySecret.set(null);
        } catch (CredentialVerificationException e) {
            cleanupIdentityMarker(snapshot.account(), identitySecret.get(),
                    identityMarker.get());
            recordActivationVerificationFailure(snapshot, e.category());
            throw e;
        } catch (RuntimeException e) {
            cleanupIdentityMarker(snapshot.account(), identitySecret.get(),
                    identityMarker.get());
            throw e;
        }
        // Every key funded by this account fails to provision while the
        // account has no usable management credential, and each failure
        // lengthens its own wait. A working credential is now in place, so
        // the waits are answers to a question that has been resolved: clear
        // them and let the next sweep try, rather than leaving keys out for
        // hours after the operator has already fixed the cause.
        tx.executeWithoutResult(status ->
                keyRepository.clearOpenrouterBackoffForAccount(snapshot.account().getId()));
        creditRefreshScheduler.requestAfterCredentialChange(accountId);
        return result;
    }

    @Transactional
    public OpenRouterAccountResponse cancel(AuthenticatedUser actor, UUID accountId,
            ConfirmOpenRouterAccountRequest form, String ip) {
        OpenRouterAccount account = requireWritableWithLock(actor, accountId);
        confirm(account.getName(), form.confirmName());
        OpenRouterAccountCredential staged = credentialRepository
                .findByAccountIdAndStatus(account.getId(), OpenRouterCredentialStatus.STAGED)
                .orElseThrow(() -> credentialState("취소할 STAGED credential이 없습니다."));
        credentialRepository.delete(staged);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.OPENROUTER_CREDENTIAL_CANCEL, "openrouter_account",
                account.getPublicId(), Map.of(), ip);
        return response(account);
    }

    public OpenRouterAccountResponse rollback(AuthenticatedUser actor, UUID accountId,
            ConfirmOpenRouterAccountRequest form, String ip) {
        RotationSnapshot snapshot = rotationSnapshot(actor, accountId, form.confirmName());
        validateCredentialNow(snapshot.account(), snapshot.retiring(),
                OpenRouterCredentialStatus.RETIRING);
        OpenRouterAccountResponse result = tx.execute(status -> {
            OpenRouterAccount account = requireWritableWithLock(actor, accountId);
            List<OpenRouterAccountCredential> credentials =
                    credentialRepository.findAllWithLockByAccountId(account.getId());
            OpenRouterAccountCredential active = byId(credentials, snapshot.active().getId());
            OpenRouterAccountCredential retiring = byId(credentials, snapshot.retiring().getId());
            if (active.getStatus() != OpenRouterCredentialStatus.ACTIVE
                    || retiring.getStatus() != OpenRouterCredentialStatus.RETIRING) {
                throw credentialState("검증 뒤 credential 상태가 바뀌었습니다. 다시 시도해 주세요.");
            }
            entityManager.createNativeQuery(
                    "set constraints openrouter_account_credentials_slot_uq deferred")
                    .executeUpdate();
            Instant now = Instant.now();
            active.restoreToStaged(now);
            retiring.markUsed(now);
            retiring.activate(now);
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    AuditService.OPENROUTER_CREDENTIAL_ROLLBACK, "openrouter_account",
                    account.getPublicId(), Map.of(), ip);
            return response(account);
        });
        // Every key funded by this account fails to provision while the
        // account has no usable management credential, and each failure
        // lengthens its own wait. A working credential is now in place, so
        // the waits are answers to a question that has been resolved: clear
        // them and let the next sweep try, rather than leaving keys out for
        // hours after the operator has already fixed the cause.
        tx.executeWithoutResult(status ->
                keyRepository.clearOpenrouterBackoffForAccount(snapshot.account().getId()));
        creditRefreshScheduler.requestAfterCredentialChange(accountId);
        return result;
    }

    public OpenRouterAccountResponse finalizeRetiring(AuthenticatedUser actor, UUID accountId,
            FinalizeOpenRouterCredentialRequest form, String ip) {
        requireVendorRevocation(form);
        RotationSnapshot snapshot = rotationSnapshot(actor, accountId, form.confirmName());
        requireReconciledAfterActivation(snapshot.active());
        validateCredentialNow(snapshot.account(), snapshot.active(),
                OpenRouterCredentialStatus.ACTIVE);
        assertVendorCredentialRevoked(snapshot.account(), snapshot.retiring());
        return tx.execute(status -> {
            OpenRouterAccount account = requireWritableWithLock(actor, accountId);
            List<OpenRouterAccountCredential> credentials =
                    credentialRepository.findAllWithLockByAccountId(account.getId());
            OpenRouterAccountCredential active = byId(credentials, snapshot.active().getId());
            OpenRouterAccountCredential retiring = byId(credentials, snapshot.retiring().getId());
            if (active.getStatus() != OpenRouterCredentialStatus.ACTIVE
                    || retiring.getStatus() != OpenRouterCredentialStatus.RETIRING) {
                throw credentialState("검증 뒤 credential 상태가 바뀌었습니다. 다시 시도해 주세요.");
            }
            requireReconciledAfterActivation(active);
            active.markUsed(Instant.now());
            credentialRepository.delete(retiring);
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    AuditService.OPENROUTER_CREDENTIAL_FINALIZE, "openrouter_account",
                    account.getPublicId(), Map.of("vendorRevocationConfirmed", true), ip);
            return response(account);
        });
    }

    public OpenRouterAccountResponse deleteActive(AuthenticatedUser actor, UUID accountId,
            FinalizeOpenRouterCredentialRequest form, String ip) {
        requireVendorRevocation(form);
        ActiveDeletionSnapshot snapshot = activeDeletionSnapshot(
                actor, accountId, form.confirmName());
        assertVendorCredentialRevoked(snapshot.account(), snapshot.active());
        return tx.execute(status -> {
            OpenRouterAccount account = requireWritableWithLock(actor, accountId);
            List<OpenRouterAccountCredential> credentials =
                    credentialRepository.findAllWithLockByAccountId(account.getId());
            requireActiveDeletionSafe(account, credentials);
            OpenRouterAccountCredential active = byId(credentials, snapshot.active().getId());
            if (active.getStatus() != OpenRouterCredentialStatus.ACTIVE) {
                throw credentialState("검증 뒤 credential 상태가 바뀌었습니다. 다시 시도해 주세요.");
            }
            credentialRepository.delete(active);
            auditService.recordAfterCommit(actor.id(), actor.role().name(),
                    AuditService.OPENROUTER_CREDENTIAL_DELETE, "openrouter_account",
                    account.getPublicId(), Map.of("vendorRevocationConfirmed", true), ip);
            return response(account);
        });
    }

    private UUID validateCandidate(OpenRouterAccount account, String secret) {
        OpenRouterClient.CreatedKey created = null;
        UUID requestedWorkspace = account.getVendorWorkspaceId();
        try {
            client.credits(secret);
            client.listKeys(secret, requestedWorkspace);
            created = client.createKey(secret, requestedWorkspace,
                    "pickle-credential-probe-" + account.getPublicId(), BigDecimal.ZERO,
                    null, Instant.now().plus(Duration.ofMinutes(5)));
            UUID observed = created.workspaceId();
            if (observed == null
                    || (requestedWorkspace != null && !requestedWorkspace.equals(observed))) {
                throw new OpenRouterException(0, "workspace identity mismatch");
            }
            client.setDisabled(secret, observed, created.hash(), true);
            client.deleteKey(secret, observed, created.hash());
            return observed;
        } catch (OpenRouterException | IllegalStateException e) {
            if (created != null) {
                try {
                    client.deleteKey(secret,
                            created.workspaceId() != null ? created.workspaceId() : requestedWorkspace,
                            created.hash());
                } catch (RuntimeException ignored) { }
            }
            throw verificationFailed(classifyVerificationError(e));
        }
    }

    private void rejectSharedVendorAccount(OpenRouterAccount account, String candidateSecret) {
        for (OpenRouterAccount other : accountRepository.findAll()) {
            if (other.getId().equals(account.getId())
                    || other.getVendorIdentityKeyHash() == null
                    || other.getVendorWorkspaceId() == null) {
                continue;
            }
            try {
                OpenRouterClient.ManagedKey identity = client.getKey(candidateSecret,
                        other.getVendorWorkspaceId(), other.getVendorIdentityKeyHash());
                if (identity != null) {
                    throw credentialState(
                            "같은 OpenRouter billing account가 이미 등록되어 있습니다.");
                }
            } catch (OpenRouterException error) {
                if (error.status() != 404) {
                    throw verificationFailed(classifyVerificationError(error));
                }
                OpenRouterAccountCredential active = credentialRepository
                        .findByAccountIdAndStatus(other.getId(),
                                OpenRouterCredentialStatus.ACTIVE)
                        .orElse(null);
                if (active == null) {
                    throw credentialState(
                            "기존 account의 vendor identity marker를 확인할 수 없습니다. 먼저 해당 account의 credential을 복구해 주세요.");
                }
                if (sharesManagementScope(other, decrypt(other, active),
                        other.getVendorWorkspaceId(), candidateSecret)) {
                    throw credentialState(
                            "같은 OpenRouter billing account가 이미 등록되어 있습니다.");
                }
            }
        }
        for (OpenRouterAccountCredential credential : credentialRepository
                .findByStatus(OpenRouterCredentialStatus.ACTIVE)) {
            if (credential.getAccountId().equals(account.getId())) {
                continue;
            }
            OpenRouterAccount other = accountRepository.findById(credential.getAccountId())
                    .orElse(null);
            if (other == null || other.getVendorWorkspaceId() == null
                    || other.getVendorIdentityKeyHash() != null) {
                continue;
            }
            String existingSecret = decrypt(other, credential);
            if (sharesManagementScope(other, existingSecret, other.getVendorWorkspaceId(),
                    candidateSecret)) {
                throw credentialState("같은 OpenRouter billing account가 이미 등록되어 있습니다.");
            }
        }
    }

    private OpenRouterClient.CreatedKey createIdentityMarker(OpenRouterAccount account,
            String secret) {
        OpenRouterClient.CreatedKey created = null;
        try {
            created = client.createKey(secret, account.getVendorWorkspaceId(),
                    "pickle-billing-identity-" + account.getPublicId(), BigDecimal.ZERO,
                    null, null);
            if (created == null) {
                throw new OpenRouterException(0, "identity marker creation returned no key");
            }
            requiredWorkspace(account, created.workspaceId());
            client.setDisabled(secret, account.getVendorWorkspaceId(), created.hash(), true);
            return created;
        } catch (OpenRouterException | IllegalStateException error) {
            cleanupIdentityMarker(account, secret, created);
            throw verificationFailed(classifyVerificationError(error));
        }
    }

    private void cleanupIdentityMarker(OpenRouterAccount account, @Nullable String secret,
            OpenRouterClient.@Nullable CreatedKey created) {
        if (secret == null || created == null) {
            return;
        }
        try {
            client.deleteKey(secret, account.getVendorWorkspaceId(), created.hash());
        } catch (RuntimeException ignored) { }
    }

    private boolean sharesManagementScope(OpenRouterAccount owner, String existingSecret,
            UUID existingWorkspace, String candidateSecret) {
        OpenRouterClient.CreatedKey created = null;
        try {
            created = client.createKey(existingSecret, existingWorkspace,
                    "pickle-account-identity-probe-" + owner.getPublicId(), BigDecimal.ZERO,
                    null, Instant.now().plus(Duration.ofMinutes(5)));
            if (created.workspaceId() == null
                    || !existingWorkspace.equals(created.workspaceId())) {
                throw new OpenRouterException(0, "workspace identity mismatch");
            }
            return client.getKey(candidateSecret, existingWorkspace, created.hash()) != null;
        } catch (OpenRouterException error) {
            if (error.status() == 404) {
                return false;
            }
            throw verificationFailed(classifyVerificationError(error));
        } catch (IllegalStateException error) {
            throw verificationFailed(classifyVerificationError(error));
        } finally {
            if (created != null) {
                try {
                    client.deleteKey(existingSecret, existingWorkspace, created.hash());
                } catch (RuntimeException ignored) { }
            }
        }
    }

    private void crossManagementProbe(OpenRouterAccount account, String oldSecret,
            String newSecret) {
        OpenRouterClient.CreatedKey created = null;
        try {
            created = client.createKey(oldSecret, account.getVendorWorkspaceId(),
                    "pickle-rotation-probe-" + account.getPublicId(), BigDecimal.ZERO,
                    null, Instant.now().plus(Duration.ofMinutes(5)));
            UUID workspace = requiredWorkspace(account, created.workspaceId());
            client.setDisabled(oldSecret, workspace, created.hash(), true);
            client.getKey(newSecret, workspace, created.hash());
            client.updateLimit(newSecret, workspace, created.hash(), BigDecimal.ZERO, null);
            client.deleteKey(newSecret, workspace, created.hash());
        } catch (OpenRouterException | IllegalStateException e) {
            if (created != null) {
                try {
                    client.deleteKey(oldSecret,
                            created.workspaceId() != null ? created.workspaceId()
                                    : account.getVendorWorkspaceId(), created.hash());
                } catch (RuntimeException ignored) { }
            }
            throw verificationFailed(classifyVerificationError(e));
        }
    }

    private void recordActivationVerificationFailure(CredentialSnapshot snapshot,
            OpenRouterCredentialError error) {
        tx.executeWithoutResult(status -> {
            OpenRouterAccount locked = accountRepository
                    .findWithLockByPublicId(snapshot.account().getPublicId()).orElse(null);
            if (locked == null) {
                return;
            }
            credentialRepository.findAllWithLockByAccountId(locked.getId()).stream()
                    .filter(credential -> credential.getId().equals(snapshot.staged().getId()))
                    .filter(credential -> credential.getStatus()
                            == OpenRouterCredentialStatus.STAGED)
                    .findFirst()
                    .ifPresent(credential -> credential.recordVerificationFailure(
                            error, Instant.now()));
        });
    }

    private static UUID requiredWorkspace(OpenRouterAccount account, @Nullable UUID observed) {
        if (observed == null || account.getVendorWorkspaceId() == null
                || !account.getVendorWorkspaceId().equals(observed)) {
            throw new OpenRouterException(0, "workspace identity mismatch");
        }
        return observed;
    }

    private CredentialSnapshot credentialSnapshot(AuthenticatedUser actor, UUID accountId,
            String confirmName) {
        return tx.execute(status -> {
            OpenRouterAccount account = requireWritable(actor, accountId);
            confirm(account.getName(), confirmName);
            List<OpenRouterAccountCredential> credentials =
                    credentialRepository.findByAccountIdOrderByIdAsc(account.getId());
            return new CredentialSnapshot(account,
                    optionalByStatus(credentials, OpenRouterCredentialStatus.ACTIVE),
                    optionalByStatus(credentials, OpenRouterCredentialStatus.STAGED));
        });
    }

    private RotationSnapshot rotationSnapshot(AuthenticatedUser actor, UUID accountId,
            String confirmName) {
        return tx.execute(status -> {
            OpenRouterAccount account = requireWritableWithLock(actor, accountId);
            confirm(account.getName(), confirmName);
            List<OpenRouterAccountCredential> credentials =
                    credentialRepository.findAllWithLockByAccountId(account.getId());
            return new RotationSnapshot(account,
                    byStatus(credentials, OpenRouterCredentialStatus.ACTIVE),
                    byStatus(credentials, OpenRouterCredentialStatus.RETIRING));
        });
    }

    private ActiveDeletionSnapshot activeDeletionSnapshot(AuthenticatedUser actor, UUID accountId,
            String confirmName) {
        return tx.execute(status -> {
            OpenRouterAccount account = requireWritableWithLock(actor, accountId);
            confirm(account.getName(), confirmName);
            List<OpenRouterAccountCredential> credentials =
                    credentialRepository.findAllWithLockByAccountId(account.getId());
            requireActiveDeletionSafe(account, credentials);
            return new ActiveDeletionSnapshot(account,
                    byStatus(credentials, OpenRouterCredentialStatus.ACTIVE));
        });
    }

    private String validateCredentialNow(OpenRouterAccount account,
            OpenRouterAccountCredential credential, OpenRouterCredentialStatus expectedStatus) {
        String secret = decrypt(account, credential);
        try {
            client.credits(secret);
            recordCredentialVerification(account, credential.getId(), expectedStatus, null);
            return secret;
        } catch (OpenRouterException | IllegalStateException e) {
            OpenRouterCredentialError error = classifyVerificationError(e);
            recordCredentialVerification(account, credential.getId(), expectedStatus, error);
            throw verificationFailed(error);
        }
    }

    private void recordCredentialVerification(OpenRouterAccount account, long credentialId,
            OpenRouterCredentialStatus expectedStatus,
            @Nullable OpenRouterCredentialError error) {
        tx.executeWithoutResult(status -> {
            OpenRouterAccount locked = accountRepository
                    .findWithLockByPublicId(account.getPublicId()).orElse(null);
            if (locked == null) {
                return;
            }
            credentialRepository.findAllWithLockByAccountId(locked.getId()).stream()
                    .filter(credential -> credential.getId() == credentialId)
                    .filter(credential -> credential.getStatus() == expectedStatus)
                    .findFirst()
                    .ifPresent(credential -> {
                        Instant now = Instant.now();
                        if (error == null) {
                            credential.recordVerificationSuccess(now);
                        } else {
                            credential.recordVerificationFailure(error, now);
                        }
                    });
        });
    }

    private void assertVendorCredentialRevoked(OpenRouterAccount account,
            OpenRouterAccountCredential credential) {
        String secret = decrypt(account, credential);
        try {
            client.credits(secret);
        } catch (OpenRouterException e) {
            if (e.status() == 401 || e.status() == 403) {
                return;
            }
            throw verificationFailed(classifyVerificationError(e));
        } catch (IllegalStateException e) {
            throw verificationFailed(classifyVerificationError(e));
        }
        throw credentialState(
                "Vendor console에서 대상 management credential을 먼저 폐기해 주세요.");
    }

    private void requireActiveDeletionSafe(OpenRouterAccount account,
            List<OpenRouterAccountCredential> credentials) {
        if (credentials.stream().anyMatch(c -> c.getStatus()
                != OpenRouterCredentialStatus.ACTIVE)) {
            throw credentialState("Rotation credential을 먼저 취소하거나 finalize해 주세요.");
        }
        if (account.getVendorIdentityKeyHash() == null) {
            throw credentialState(
                    "Vendor identity marker를 확정할 credential rotation이 먼저 필요합니다.");
        }
        if (keyRepository.countByOpenrouterAccountId(account.getId()) > 0) {
            throw credentialState("Key가 연결된 account의 ACTIVE credential은 삭제할 수 없습니다.");
        }
    }

    private static void requireReconciledAfterActivation(
            OpenRouterAccountCredential credential) {
        if (credential.getActivatedAt() == null || credential.getLastReconciledAt() == null
                || credential.getLastReconciledAt().isBefore(credential.getActivatedAt())) {
            throw credentialState("새 ACTIVE credential로 성공한 key reconciliation이 필요합니다.");
        }
    }

    private static boolean sameSecret(String first, String second) {
        return MessageDigest.isEqual(first.getBytes(StandardCharsets.UTF_8),
                second.getBytes(StandardCharsets.UTF_8));
    }

    private String decrypt(OpenRouterAccount account, OpenRouterAccountCredential credential) {
        try {
            return credentialCipher.decrypt(account.getPublicId(), credential.getCredentialEnc());
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    ErrorCodes.OPENROUTER_CREDENTIAL_KEYRING_UNAVAILABLE,
                    "Credential을 사용할 수 없습니다",
                    "저장된 OpenRouter management credential을 복호화할 수 없습니다.");
        }
    }

    private OpenRouterAccount requireReadable(AuthenticatedUser actor, UUID accountId) {
        OpenRouterAccount account = accountRepository.findByPublicId(accountId)
                .orElseThrow(AdminOpenRouterAccountService::notFound);
        if (actor.role().isOrgTier() && !actor.reads(account.getOrgId())) {
            throw notFound();
        }
        return account;
    }

    private OpenRouterAccount requireWritable(AuthenticatedUser actor, UUID accountId) {
        OpenRouterAccount account = requireReadable(actor, accountId);
        if (actor.role().isOrgTier()) {
            if (!actor.operates(account.getOrgId())) {
                throw notFound();
            }
            return account;
        }
        if (actor.role() == UserRole.SYS_ADMIN) {
            return account;
        }
        throw denied("이 account를 변경할 권한이 없습니다.");
    }

    private OpenRouterAccount requireWritableWithLock(AuthenticatedUser actor, UUID accountId) {
        OpenRouterAccount account = accountRepository.findWithLockByPublicId(accountId)
                .orElseThrow(AdminOpenRouterAccountService::notFound);
        if (actor.role().isOrgTier() && !actor.operates(account.getOrgId())) {
            throw notFound();
        }
        if (!actor.role().isOrgTier() && actor.role() != UserRole.SYS_ADMIN) {
            throw denied("이 account를 변경할 권한이 없습니다.");
        }
        return account;
    }

    private Org requireWritableOrg(AuthenticatedUser actor, UUID orgId) {
        Org org = orgRepository.findByPublicId(orgId)
                .orElseThrow(AdminOpenRouterAccountService::notFound);
        if (actor.role().isOrgTier() && !actor.operates(org.getId())) {
            throw notFound();
        }
        if (!actor.role().isOrgTier() && actor.role() != UserRole.SYS_ADMIN) {
            throw denied("이 기관에 account를 만들 권한이 없습니다.");
        }
        return org;
    }

    private OpenRouterAccountResponse response(OpenRouterAccount account) {
        return response(account, allocationQuery.forAccount(account.getId()));
    }

    /**
     * The list path gathers every account's allocation in one pass and hands it
     * in; every other path asks about the single account it is answering about.
     */
    private OpenRouterAccountResponse response(OpenRouterAccount account,
            OpenRouterAllocationQuery.Allocation allocation) {
        Org org = orgRepository.findById(account.getOrgId()).orElseThrow();
        List<OpenRouterAccountCredential> credentials =
                credentialRepository.findByAccountIdOrderByIdAsc(account.getId());
        OpenRouterAccountCredential active = optionalByStatus(credentials,
                OpenRouterCredentialStatus.ACTIVE);
        return new OpenRouterAccountResponse(account.getPublicId(), org.getPublicId(), org.getName(),
                account.getName(), account.getStatus(), account.getProgram(),
                account.getContact(), accountSelection.eligible(account),
                keyRepository.countByOpenrouterAccountId(account.getId()),
                accountSelection.databaseCredentialAvailable(account),
                state(active),
                state(credentials.stream().filter(c -> c.getStatus()
                                != OpenRouterCredentialStatus.ACTIVE).findFirst().orElse(null)),
                creditsQuery.get(account), allocationResponse(allocation),
                CreditModelPatterns.fromJson(objectMapper,
                        account.getDefaultCreditAllowedModels()),
                account.getCreatedAt(), account.getUpdatedAt());
    }

    private static OpenRouterAccountAllocationResponse allocationResponse(
            OpenRouterAllocationQuery.Allocation allocation) {
        return new OpenRouterAccountAllocationResponse(allocation.committedCreditLimit(),
                allocation.committedTotalCap(), allocation.committedDaily(),
                allocation.committedWeekly(), allocation.committedMonthly(),
                allocation.committedKeyCount(), allocation.remainingCommitment(),
                allocation.committedUsage(), allocation.awaitingProvisionKeyCount(),
                allocation.usageUnreportedKeyCount());
    }

    private static @Nullable OpenRouterCredentialStateResponse state(
            @Nullable OpenRouterAccountCredential credential) {
        if (credential == null) {
            return null;
        }
        boolean overdue = credential.getStatus() == OpenRouterCredentialStatus.RETIRING
                && credential.getRetiringAt() != null
                && credential.getRetiringAt().plus(RETIRING_WARNING).isBefore(Instant.now());
        return new OpenRouterCredentialStateResponse(credential.getStatus(),
                credential.getCreatedAt(), credential.getLastVerificationAttemptAt(),
                credential.getVerifiedAt(), credential.getActivatedAt(),
                credential.getRetiringAt(), credential.getLastUsedAt(),
                credential.getLastReconciledAt(), credential.getVerificationError(), overdue);
    }

    private static void confirm(String accountName, String confirmation) {
        if (!accountName.equals(confirmation)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    ErrorCodes.OPENROUTER_ACCOUNT_CONFIRM_NAME_MISMATCH,
                    "확인용 account 이름이 일치하지 않습니다",
                    "표시된 account 이름을 정확히 입력해 주세요.");
        }
    }

    private static void requireVendorRevocation(FinalizeOpenRouterCredentialRequest form) {
        if (!Boolean.TRUE.equals(form.vendorRevocationConfirmed())) {
            throw ApiException.validationFailed(List.of(new FieldValidationError(
                    "vendorRevocationConfirmed",
                    "Vendor console에서 management key를 폐기했음을 확인해 주세요.")));
        }
    }

    private static void requireActive(OpenRouterAccount account) {
        if (account.getStatus() != OpenRouterAccountStatus.ACTIVE) {
            throw invalidState("보관된 account에는 credential을 등록할 수 없습니다.");
        }
    }

    private static @Nullable OpenRouterAccountCredential optionalByStatus(
            List<OpenRouterAccountCredential> credentials, OpenRouterCredentialStatus status) {
        return credentials.stream().filter(c -> c.getStatus() == status).findFirst().orElse(null);
    }

    private static OpenRouterAccountCredential byStatus(
            List<OpenRouterAccountCredential> credentials, OpenRouterCredentialStatus status) {
        OpenRouterAccountCredential credential = optionalByStatus(credentials, status);
        if (credential == null) {
            throw credentialState(status + " credential이 없습니다.");
        }
        return credential;
    }

    private static OpenRouterAccountCredential byId(
            List<OpenRouterAccountCredential> credentials, long id) {
        return credentials.stream().filter(c -> c.getId() == id).findFirst()
                .orElseThrow(() -> credentialState("Credential 상태가 바뀌었습니다."));
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 OpenRouter account를 찾을 수 없습니다.");
    }

    private static ApiException denied(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                "접근 권한이 없습니다", detail);
    }

    private static ApiException invalidState(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.OPENROUTER_ACCOUNT_INVALID_STATE,
                "Account 상태가 올바르지 않습니다", detail);
    }

    private static ApiException credentialState(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.OPENROUTER_CREDENTIAL_INVALID_STATE,
                "Credential 상태가 올바르지 않습니다", detail);
    }

    private static ApiException workspaceConflict() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.OPENROUTER_ACCOUNT_INVALID_STATE,
                "Vendor workspace가 이미 등록되어 있습니다",
                "같은 OpenRouter vendor workspace를 두 사업 계정에 등록할 수 없습니다.");
    }

    private static ApiException duplicateName() {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.OPENROUTER_ACCOUNT_INVALID_STATE,
                "같은 이름의 account가 이미 있습니다",
                "한 기관 안에서는 사업 계정 이름을 중복해서 사용할 수 없습니다.");
    }

    private static CredentialVerificationException verificationFailed(
            OpenRouterCredentialError category) {
        return new CredentialVerificationException(category);
    }

    private static OpenRouterCredentialError classifyVerificationError(RuntimeException error) {
        if (error instanceof OpenRouterException vendor) {
            if (vendor.status() == 401 || vendor.status() == 403) {
                return OpenRouterCredentialError.CREDENTIAL_ERROR;
            }
            if (vendor.status() == 429) {
                return OpenRouterCredentialError.THROTTLED;
            }
            if (vendor.status() == 0 || vendor.status() >= 500) {
                return OpenRouterCredentialError.VENDOR_UNAVAILABLE;
            }
        }
        return OpenRouterCredentialError.VENDOR_REJECTED;
    }

    private static final class CredentialVerificationException extends ApiException {
        private final OpenRouterCredentialError category;

        private CredentialVerificationException(OpenRouterCredentialError category) {
            super(HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorCodes.OPENROUTER_CREDENTIAL_VERIFICATION_FAILED,
                "OpenRouter management credential을 검증하지 못했습니다",
                "Credential 권한과 vendor account workspace를 확인해 주세요.");
            this.category = category;
        }

        private OpenRouterCredentialError category() {
            return category;
        }
    }

    private record CredentialSnapshot(OpenRouterAccount account,
            @Nullable OpenRouterAccountCredential active,
            @Nullable OpenRouterAccountCredential staged) {
    }

    private record RotationSnapshot(OpenRouterAccount account,
            OpenRouterAccountCredential active,
            OpenRouterAccountCredential retiring) {
    }

    private record ActiveDeletionSnapshot(OpenRouterAccount account,
            OpenRouterAccountCredential active) {
    }
}
