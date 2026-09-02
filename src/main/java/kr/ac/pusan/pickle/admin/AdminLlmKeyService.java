package kr.ac.pusan.pickle.admin;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeyDetailResponse;
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeyLimitsRequest;
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeySummaryResponse;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.llm.LlmGatewayGenerations;
import kr.ac.pusan.pickle.llm.openrouter.LlmOpenRouterProvisioner;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccount;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountSelectionService;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAllocationQuery;
import kr.ac.pusan.pickle.orgs.AdminOrgScope;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgScope;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestRepository;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Administrator reads and state changes for LLM API keys. */
@Service
public class AdminLlmKeyService {

    private final LlmApiKeyRepository keyRepository;
    private final WorkspaceRepository workspaceRepository;
    private final OrgRepository orgRepository;
    private final RequestRepository requestRepository;
    private final LlmGatewayGenerations generations;
    private final LlmOpenRouterProvisioner provisioner;
    private final AuditService auditService;
    private final EntityManager entityManager;
    private final OpenRouterAccountRepository accountRepository;
    private final OpenRouterAccountSelectionService accountSelection;
    private final OpenRouterAllocationQuery allocationQuery;

    public AdminLlmKeyService(LlmApiKeyRepository keyRepository,
            WorkspaceRepository workspaceRepository, OrgRepository orgRepository,
            RequestRepository requestRepository, LlmGatewayGenerations generations,
            LlmOpenRouterProvisioner provisioner, AuditService auditService,
            EntityManager entityManager, OpenRouterAccountRepository accountRepository,
            OpenRouterAccountSelectionService accountSelection,
            OpenRouterAllocationQuery allocationQuery) {
        this.keyRepository = keyRepository;
        this.workspaceRepository = workspaceRepository;
        this.orgRepository = orgRepository;
        this.requestRepository = requestRepository;
        this.generations = generations;
        this.provisioner = provisioner;
        this.auditService = auditService;
        this.entityManager = entityManager;
        this.accountRepository = accountRepository;
        this.accountSelection = accountSelection;
        this.allocationQuery = allocationQuery;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminLlmKeySummaryResponse> list(AuthenticatedUser actor, UUID orgId,
            UUID workspaceId, UUID requestId, UUID openrouterAccountId,
            LlmApiKeyStatus status, String query, int page, int size) {
        Long requestedOrgId = orgId == null ? null
                : orgRepository.findByPublicId(orgId).map(Org::getId).orElse(null);
        OrgScope scope = AdminOrgScope.read(actor, orgId, requestedOrgId);
        Long requestedWorkspaceId = workspaceId == null ? null
                : workspaceRepository.findByPublicId(workspaceId).map(Workspace::getId).orElse(null);
        Long requestedRequestId = requestId == null ? null
                : requestRepository.findByPublicId(requestId).map(Request::getId).orElse(null);
        Long requestedAccountId = openrouterAccountId == null ? null
                : accountRepository.findByPublicId(openrouterAccountId)
                        .map(OpenRouterAccount::getId).orElse(null);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        if ((!scope.isUnrestricted() && scope.orgIds().isEmpty())
                || (workspaceId != null && requestedWorkspaceId == null)
                || (requestId != null && requestedRequestId == null)
                || (openrouterAccountId != null && requestedAccountId == null)) {
            return PageResponse.of(List.of(), Page.empty(pageable));
        }

        Instant now = Instant.now();
        Specification<LlmApiKey> spec = (root, ignored, cb) -> cb.conjunction();
        if (!scope.isUnrestricted()) {
            spec = spec.and((root, ignored, cb) -> root.get("orgId").in(scope.orgIds()));
        }
        if (requestedWorkspaceId != null) {
            spec = spec.and((root, ignored, cb) -> cb.equal(root.get("workspaceId"),
                    requestedWorkspaceId));
        }
        if (requestedRequestId != null) {
            spec = spec.and((root, ignored, cb) -> cb.equal(root.get("requestId"),
                    requestedRequestId));
        }
        if (requestedAccountId != null) {
            spec = spec.and((root, ignored, cb) -> cb.equal(root.get("openrouterAccountId"),
                    requestedAccountId));
        }
        if (status != null) {
            if (status == LlmApiKeyStatus.EXPIRED) {
                spec = spec.and((root, ignored, cb) -> cb.or(
                        cb.equal(root.get("status"), LlmApiKeyStatus.EXPIRED),
                        cb.and(cb.notEqual(root.get("status"), LlmApiKeyStatus.REVOKED),
                                cb.lessThanOrEqualTo(root.get("expiresAt"), now))));
            } else if (status == LlmApiKeyStatus.REVOKED) {
                spec = spec.and((root, ignored, cb) -> cb.equal(root.get("status"), status));
            } else {
                spec = spec.and((root, ignored, cb) -> cb.and(
                        cb.equal(root.get("status"), status),
                        cb.or(cb.isNull(root.get("expiresAt")),
                                cb.greaterThan(root.get("expiresAt"), now))));
            }
        }
        if (query != null && !query.isBlank()) {
            String pattern = "%" + escapeLike(query.strip().toLowerCase()) + "%";
            spec = spec.and((root, ignored, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), pattern, '\\'),
                    cb.like(cb.lower(root.get("purpose")), pattern, '\\')));
        }

        Page<LlmApiKey> result = keyRepository.findAll(spec, pageable);
        References refs = references(result.getContent());
        List<AdminLlmKeySummaryResponse> content = result.getContent().stream()
                .map(key -> {
                    Workspace workspace = refs.workspaces().get(key.getWorkspaceId());
                    Org org = refs.orgs().get(key.getOrgId());
                    OpenRouterAccount account = refs.accounts().get(key.getOpenrouterAccountId());
                    return AdminLlmKeySummaryResponse.from(key,
                            workspace == null ? null : workspace.getPublicId(),
                            workspace == null ? "" : workspace.getName(),
                            org == null ? null : org.getPublicId(),
                            org == null ? "" : org.getName(),
                            refs.requests().containsKey(key.getRequestId())
                                    ? refs.requests().get(key.getRequestId()).getPublicId() : null,
                            account == null ? null : account.getPublicId(),
                            account == null ? null : account.getName(),
                            now);
                })
                .toList();
        return PageResponse.of(content, result);
    }

    @Transactional(readOnly = true)
    public AdminLlmKeyDetailResponse get(AuthenticatedUser actor, UUID keyId) {
        LlmApiKey key = requireReadable(actor, keyId);
        Workspace workspace = workspaceRepository.findById(key.getWorkspaceId()).orElse(null);
        Org org = orgRepository.findById(key.getOrgId()).orElse(null);
        UUID requestId = requestRepository.findById(key.getRequestId())
                .map(Request::getPublicId).orElse(null);
        OpenRouterAccount account = key.getOpenrouterAccountId() == null ? null
                : accountRepository.findById(key.getOpenrouterAccountId()).orElse(null);
        return AdminLlmKeyDetailResponse.from(key,
                workspace == null ? null : workspace.getPublicId(),
                workspace == null ? "" : workspace.getName(),
                org == null ? null : org.getPublicId(), org == null ? "" : org.getName(),
                requestId, account == null ? null : account.getPublicId(),
                account == null ? null : account.getName(), Instant.now());
    }

    @Transactional
    public AdminLlmKeyDetailResponse replaceLimits(AuthenticatedUser actor, UUID keyId,
            AdminLlmKeyLimitsRequest form, String ip) {
        if (!form.isComplete()) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("limits",
                    "여섯 한도 값을 모두 보내 주세요. 한도를 비우려면 null을 명시해 주세요.")));
        }
        if (form.getCreditLimit() == null) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("creditLimit",
                    "금액 한도는 null일 수 없습니다. 유료 모델을 닫으려면 0을 보내 주세요.")));
        }
        if (form.getTpm() != null && form.getRpm() != null && form.getTpm() < form.getRpm()) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("tpm",
                    "분당 토큰 수는 분당 요청 수보다 작을 수 없습니다.")));
        }
        if (form.getCreditLimitReset() != null && form.getCreditLimit().signum() <= 0) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("creditLimit",
                    "리셋 창을 두려면 0보다 큰 금액 한도가 필요합니다.")));
        }
        LlmApiKey key = requireWritable(actor, keyId);
        generations.bump();
        entityManager.refresh(key);
        requireMutableStatus(key, "한도를 변경할");
        boolean moneyChanged = key.getCreditLimit().compareTo(form.getCreditLimit()) != 0
                || !Objects.equals(key.getCreditLimitReset(), form.getCreditLimitReset());
        boolean bindingRequested = form.getOpenrouterAccountId() != null
                && key.getOpenrouterAccountId() == null;
        if (actor.role() == UserRole.SYS_MANAGER && (moneyChanged || bindingRequested)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                    "접근 권한이 없습니다", "시스템 운영자는 금액 한도를 변경할 수 없습니다.");
        }
        OpenRouterAccount account = resolveLimitAccount(key, form);
        boolean bindingChanged = key.getOpenrouterAccountId() == null && account != null;

        // Read the account's standing commitment before the key is mutated. The
        // key's own old limit is already inside that sum when it is bound to the
        // same account — requireMutableStatus has just established that it is
        // live — so what this write changes is the difference, not the new figure.
        // A key binding for the first time is not in the sum yet and adds all of it.
        Map<String, Object> allocationRecord = Map.of();
        if (account != null) {
            BigDecimal counted = Objects.equals(key.getOpenrouterAccountId(), account.getId())
                    ? key.getCreditLimit() : BigDecimal.ZERO;
            allocationRecord = allocationQuery.grantRecord(account.getId(),
                    form.getCreditLimit().subtract(counted));
        }

        key.replaceLimits(form.getRpm(), form.getTpm(), form.getConcurrency(),
                form.getDailyTokens(), form.getCreditLimit(), form.getCreditLimitReset(),
                Instant.now());
        if (bindingChanged) {
            key.bindOpenrouterAccount(account.getId(), Instant.now());
        }
        if (moneyChanged || bindingChanged) {
            // Somebody just changed the money grant or its account, which is
            // the usual answer to whatever made provisioning fail. Make the
            // key eligible again now rather than leaving it to sit out a wait
            // sized for a vendor that keeps refusing.
            //
            // Only for those two. A rate-limit edit says nothing about why
            // provisioning failed, and clearing on any change at all would
            // let one unrelated save on one key release a whole throttled
            // batch onto the next sweep.
            key.clearOpenrouterBackoff(Instant.now());
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("rpm", form.getRpm());
        args.put("tpm", form.getTpm());
        args.put("concurrency", form.getConcurrency());
        args.put("dailyTokens", form.getDailyTokens());
        args.put("creditLimit", form.getCreditLimit());
        args.put("creditLimitReset", form.getCreditLimitReset());
        args.put("openrouterAccountId", account == null ? null : account.getPublicId());
        args.putAll(allocationRecord);
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.LLM_KEY_LIMITS_UPDATE, "llm_key", key.getPublicId(), args, ip);
        if (moneyChanged && key.getOpenrouterKeyHash() != null) {
            String hash = key.getOpenrouterKeyHash();
            BigDecimal limit = key.getCreditLimit();
            var reset = key.getCreditLimitReset();
            long internalKeyId = key.getId();
            afterCommit(() -> provisioner.updateLimitAfterChange(
                    internalKeyId, hash, limit, reset));
        }
        return get(actor, keyId);
    }

    @Transactional
    public AdminLlmKeyDetailResponse suspend(AuthenticatedUser actor, UUID keyId, String reason,
            String ip) {
        LlmApiKey key = requireWritable(actor, keyId);
        generations.bump();
        entityManager.refresh(key);
        if (key.effectiveStatus(Instant.now()) != LlmApiKeyStatus.ACTIVE) {
            throw invalidState("활성 상태의 키만 정지할 수 있습니다.");
        }
        key.suspend(Instant.now());
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.LLM_KEY_SUSPEND, "llm_key", key.getPublicId(),
                Map.of("reason", reason.strip()), ip);
        if (key.getOpenrouterKeyHash() != null) {
            String hash = key.getOpenrouterKeyHash();
            long internalKeyId = key.getId();
            afterCommit(() -> provisioner.setDisabledAfterStatusChange(
                    internalKeyId, hash, true));
        }
        return get(actor, keyId);
    }

    @Transactional
    public AdminLlmKeyDetailResponse resume(AuthenticatedUser actor, UUID keyId, String ip) {
        LlmApiKey key = requireWritable(actor, keyId);
        generations.bump();
        entityManager.refresh(key);
        if (key.effectiveStatus(Instant.now()) != LlmApiKeyStatus.SUSPENDED) {
            throw invalidState("정지 상태의 키만 다시 활성화할 수 있습니다.");
        }
        key.resume(Instant.now());
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.LLM_KEY_RESUME, "llm_key", key.getPublicId(), Map.of(), ip);
        if (key.getOpenrouterKeyHash() != null) {
            String hash = key.getOpenrouterKeyHash();
            long internalKeyId = key.getId();
            afterCommit(() -> provisioner.setDisabledAfterStatusChange(
                    internalKeyId, hash, false));
        }
        return get(actor, keyId);
    }

    private LlmApiKey requireReadable(AuthenticatedUser actor, UUID keyId) {
        LlmApiKey key = keyRepository.findByPublicId(keyId).orElseThrow(AdminLlmKeyService::notFound);
        if (actor.role().isOrgTier() && !actor.reads(key.getOrgId())) {
            throw notFound();
        }
        return key;
    }

    private LlmApiKey requireWritable(AuthenticatedUser actor, UUID keyId) {
        LlmApiKey key = requireReadable(actor, keyId);
        if (actor.role().isOrgTier()) {
            if (!actor.operates(key.getOrgId())) {
                throw notFound();
            }
            return key;
        }
        if (actor.role() == UserRole.SYS_MANAGER || actor.role() == UserRole.SYS_ADMIN) {
            return key;
        }
        throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                "접근 권한이 없습니다", "이 키를 변경할 권한이 없습니다.");
    }

    private static void requireMutableStatus(LlmApiKey key, String action) {
        LlmApiKeyStatus status = key.effectiveStatus(Instant.now());
        if (status != LlmApiKeyStatus.PENDING && status != LlmApiKeyStatus.ACTIVE
                && status != LlmApiKeyStatus.SUSPENDED) {
            throw invalidState(status + " 상태의 키는 " + action + " 수 없습니다.");
        }
    }

    private References references(List<LlmApiKey> keys) {
        Map<Long, Workspace> workspaces = workspaceRepository.findAllById(keys.stream()
                        .map(LlmApiKey::getWorkspaceId).distinct().toList())
                .stream().collect(Collectors.toMap(Workspace::getId, Function.identity()));
        Map<Long, Org> orgs = orgRepository.findAllById(keys.stream()
                        .map(LlmApiKey::getOrgId).distinct().toList())
                .stream().collect(Collectors.toMap(Org::getId, Function.identity()));
        Map<Long, Request> requests = requestRepository.findAllById(keys.stream()
                        .map(LlmApiKey::getRequestId).distinct().toList())
                .stream().collect(Collectors.toMap(Request::getId, Function.identity()));
        Map<Long, OpenRouterAccount> accounts = accountRepository.findAllById(keys.stream()
                        .map(LlmApiKey::getOpenrouterAccountId).filter(Objects::nonNull)
                        .distinct().toList())
                .stream().collect(Collectors.toMap(OpenRouterAccount::getId, Function.identity()));
        return new References(workspaces, orgs, requests, accounts);
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND,
                "리소스를 찾을 수 없습니다", "해당 LLM API 키를 찾을 수 없습니다.");
    }

    private static ApiException invalidState(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.LLM_KEY_INVALID_STATE,
                "키 상태가 올바르지 않습니다", detail);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private OpenRouterAccount resolveLimitAccount(LlmApiKey key, AdminLlmKeyLimitsRequest form) {
        UUID requested = form.getOpenrouterAccountId();
        if (key.getOpenrouterAccountId() != null) {
            OpenRouterAccount current = accountRepository.findById(key.getOpenrouterAccountId())
                    .orElseThrow(AdminLlmKeyService::notFound);
            if (requested != null && !current.getPublicId().equals(requested)) {
                throw immutableBinding();
            }
            return current;
        }
        // An unbound row may take its first account only while it has nothing
        // to leave behind: no money already granted, and no vendor key issued
        // under a management scope this account would not see.
        boolean firstBindingEligible = key.getCreditLimit().signum() <= 0
                && key.getOpenrouterKeyHash() == null
                && key.getOpenrouterKeyEnc() == null;
        if (firstBindingEligible) {
            return accountSelection.select(key.getOrgId(), form.getCreditLimit(), requested);
        }
        if (requested != null) {
            throw immutableBinding();
        }
        // The same key cannot fund money either, and saying so here is the
        // difference between a defined refusal and a constraint violation
        // surfacing as a 500: the write below would set a positive limit on a
        // row that names no account.
        if (form.getCreditLimit() != null && form.getCreditLimit().signum() > 0) {
            throw immutableBinding();
        }
        return null;
    }

    private static ApiException immutableBinding() {
        return new ApiException(HttpStatus.CONFLICT,
                ErrorCodes.LLM_KEY_OPENROUTER_ACCOUNT_IMMUTABLE,
                "연결된 사업 계정은 바꿀 수 없습니다",
                "다른 사업 계정으로 옮기려면 새 LLM API 키를 발급해 전환해 주세요.");
    }

    private record References(Map<Long, Workspace> workspaces, Map<Long, Org> orgs,
            Map<Long, Request> requests, Map<Long, OpenRouterAccount> accounts) {
    }
}
