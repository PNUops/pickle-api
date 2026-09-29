package kr.ac.pusan.pickle.admin;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
import kr.ac.pusan.pickle.admin.dto.AdminLlmKeyUsageResponse;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.tx.AfterCommit;
import kr.ac.pusan.pickle.common.web.PageResponse;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import kr.ac.pusan.pickle.llm.PassthroughEndpoints;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.llm.LlmGatewayGenerations;
import kr.ac.pusan.pickle.llm.LlmKeyLimitRules;
import kr.ac.pusan.pickle.llm.LlmKeyModelService;
import kr.ac.pusan.pickle.llm.LlmKeyUsageService;
import kr.ac.pusan.pickle.llm.dto.LlmKeyModelsResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyUsageTrendResponse;
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
import org.jobrunr.scheduling.JobScheduler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Administrator reads and state changes for LLM API keys. */
@Service
public class AdminLlmKeyService {

    private static final Logger log = LoggerFactory.getLogger(AdminLlmKeyService.class);

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
    private final ObjectMapper objectMapper;
    private final JobScheduler jobScheduler;
    private final LlmKeyModelService modelService;
    private final LlmKeyUsageService keyUsageService;
    private final Clock clock;

    public AdminLlmKeyService(LlmApiKeyRepository keyRepository,
            WorkspaceRepository workspaceRepository, OrgRepository orgRepository,
            RequestRepository requestRepository, LlmGatewayGenerations generations,
            LlmOpenRouterProvisioner provisioner, AuditService auditService,
            EntityManager entityManager, OpenRouterAccountRepository accountRepository,
            OpenRouterAccountSelectionService accountSelection,
            OpenRouterAllocationQuery allocationQuery, ObjectMapper objectMapper,
            JobScheduler jobScheduler, LlmKeyModelService modelService,
            LlmKeyUsageService keyUsageService, Clock clock) {
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
        this.objectMapper = objectMapper;
        this.jobScheduler = jobScheduler;
        this.modelService = modelService;
        this.keyUsageService = keyUsageService;
        this.clock = clock;
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
                account == null ? null : account.getName(),
                CreditModelPatterns.fromJson(objectMapper, key.getCreditAllowedModels(),
                        "llm key " + key.getPublicId()),
                CreditModelPatterns.fromJson(objectMapper, key.getCreditDeniedModels(),
                        "llm key " + key.getPublicId()),
                PassthroughEndpoints.fromJson(objectMapper, key.getPassthroughEndpoints(),
                        "llm key " + key.getPublicId()),
                Instant.now());
    }

    @Transactional
    public AdminLlmKeyDetailResponse replaceLimits(AuthenticatedUser actor, UUID keyId,
            AdminLlmKeyLimitsRequest form, String ip) {
        if (!form.isComplete()) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("limits",
                    "아홉 한도 값을 모두 보내 주세요. 한도를 비우려면 null을 명시해 주세요.")));
        }
        LlmKeyLimitRules.Outcome outcome = LlmKeyLimitRules.check(new LlmKeyLimitRules.Limits(
                form.getRpm(), form.getTpm(), form.getConcurrency(), form.getDailyTokens(),
                form.getCreditLimit(), form.getCreditLimitReset(), form.getCreditAllowedModels(),
                form.getCreditDeniedModels(), form.getPassthroughEndpoints()));
        if (!outcome.valid()) {
            throw ApiException.validationFailed(outcome.errors());
        }
        LlmKeyLimitRules.Normalized next = outcome.normalized();
        LlmApiKey key = requireWritable(actor, keyId);
        generations.bump();
        entityManager.refresh(key);
        requireMutableStatus(key, "한도를 변경할");
        LlmKeyLimitRules.Normalized current = currentLimits(key);
        boolean moneyChanged = moneyChanged(current, next);
        boolean bindingRequested = form.getOpenrouterAccountId() != null
                && key.getOpenrouterAccountId() == null;
        if (actor.role() == UserRole.SYS_MANAGER && (moneyChanged || bindingRequested)) {
            throw sysManagerMoneyGate();
        }
        OpenRouterAccount account = resolveLimitAccount(key, form.getOpenrouterAccountId(),
                next.creditLimit());
        boolean bindingChanged = key.getOpenrouterAccountId() == null && account != null;
        writeLimits(actor, key, current, next, account, bindingChanged, null, ip);
        return get(actor, keyId);
    }

    /**
     * The nine limits as the key holds them now, the lists decoded. What a
     * change is judged against, and what the audit records as {@code old}.
     */
    public LlmKeyLimitRules.Normalized currentLimits(LlmApiKey key) {
        String label = "llm key " + key.getPublicId();
        return new LlmKeyLimitRules.Normalized(key.getRpm(), key.getTpm(), key.getConcurrency(),
                key.getDailyTokens(), key.getCreditLimit(), key.getCreditLimitReset(),
                CreditModelPatterns.fromJson(objectMapper, key.getCreditAllowedModels(), label),
                CreditModelPatterns.fromJson(objectMapper, key.getCreditDeniedModels(), label),
                PassthroughEndpoints.fromJson(objectMapper, key.getPassthroughEndpoints(), label));
    }

    /**
     * Whether a limits write moves anything on the money side of the gate.
     *
     * <p>The two model lists belong there: both decide what the money may be
     * spent on. Left out, a SYS_MANAGER could grant a restricted key every
     * vendor on the market without touching a single number, or lift a refusal
     * through the deny list the same way. The passthrough list belongs there
     * for a sharper reason: it is the only one that grants, and a SYS_MANAGER
     * outside this gate could hand a key image generation, which settles per
     * image, without moving any number.
     */
    public static boolean moneyChanged(LlmKeyLimitRules.Normalized current,
            LlmKeyLimitRules.Normalized next) {
        return current.creditLimit().compareTo(next.creditLimit()) != 0
                || !Objects.equals(current.creditLimitReset(), next.creditLimitReset())
                || !current.creditAllowedModels().equals(next.creditAllowedModels())
                || !current.creditDeniedModels().equals(next.creditDeniedModels())
                || !current.passthroughEndpoints().equals(next.passthroughEndpoints());
    }

    /** The refusal a SYS_MANAGER meets on the money side, in one wording. */
    public static ApiException sysManagerMoneyGate() {
        return new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.ACCESS_DENIED,
                "접근 권한이 없습니다",
                "시스템 운영자는 금액 한도와 모델 목록, 기능 권한을 변경할 수 없습니다.");
    }

    /**
     * Lands one key's limits, already validated and already authorized, and
     * queues what has to follow the commit. Shared by the single replacement
     * and the bulk change, so the audit shape and the OpenRouter follow-up
     * cannot differ between them.
     *
     * <p>The caller holds the generation lock and has refreshed the key.
     */
    public void writeLimits(AuthenticatedUser actor, LlmApiKey key,
            LlmKeyLimitRules.Normalized current, LlmKeyLimitRules.Normalized next,
            @Nullable OpenRouterAccount account, boolean bindingChanged, @Nullable UUID batchId,
            String ip) {
        boolean moneyChanged = moneyChanged(current, next);
        // Read the account's standing commitment before the key is mutated. The
        // key's own old limit is already inside that sum when it is bound to the
        // same account (the key is live, or the caller would not be here), so
        // what this write changes is the difference, not the new figure. A key
        // binding for the first time is not in the sum yet and adds all of it.
        Map<String, Object> allocationRecord = Map.of();
        if (account != null) {
            BigDecimal counted = Objects.equals(key.getOpenrouterAccountId(), account.getId())
                    ? key.getCreditLimit() : BigDecimal.ZERO;
            allocationRecord = allocationQuery.grantRecord(account.getId(),
                    next.creditLimit().subtract(counted));
        }
        Instant now = Instant.now();
        key.replaceLimits(next.rpm(), next.tpm(), next.concurrency(), next.dailyTokens(),
                next.creditLimit(), next.creditLimitReset(),
                CreditModelPatterns.toJson(objectMapper, next.creditAllowedModels()),
                CreditModelPatterns.toJson(objectMapper, next.creditDeniedModels()), now);
        key.applyPassthroughEndpoints(
                PassthroughEndpoints.toJson(objectMapper, next.passthroughEndpoints()));
        if (bindingChanged) {
            key.bindOpenrouterAccount(account.getId(), now);
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
            key.clearOpenrouterBackoff(now);
        }
        Map<String, Object> args = new LinkedHashMap<>();
        // Both sides, so a reader can tell what moved without the row before.
        // The passthrough list rides in both for the reason it sits inside the
        // money gate: it changes what a key may spend on without moving any
        // number, so a record holding the numbers and not this one cannot
        // answer who opened image generation.
        args.put("old", current.asAuditFields());
        args.put("new", next.asAuditFields());
        args.put("openrouterAccountId", account == null ? null : account.getPublicId());
        args.putAll(allocationRecord);
        if (batchId != null) {
            args.put("batchId", batchId);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.LLM_KEY_LIMITS_UPDATE, "llm_key", key.getPublicId(), args, ip);
        if (moneyChanged && key.getOpenrouterKeyHash() != null) {
            String hash = key.getOpenrouterKeyHash();
            BigDecimal limit = key.getCreditLimit();
            var reset = key.getCreditLimitReset();
            long internalKeyId = key.getId();
            afterCommit("openrouter limit update " + key.getPublicId(),
                    () -> provisioner.updateLimitAfterChange(
                    internalKeyId, hash, limit, reset));
        } else if (moneyChanged && key.getCreditLimit().signum() > 0) {
            // First money on a key with no OpenRouter half yet. The branch
            // above cannot serve it (there is no hash to update), and without
            // this the key waits for the sweep, which is the same
            // several-minute silence a fresh approval used to get.
            long internalKeyId = key.getId();
            afterCommit("enqueue openrouter provision " + key.getPublicId(), () -> {
                try {
                    jobScheduler.enqueue(() -> provisioner.provision(internalKeyId));
                } catch (RuntimeException e) {
                    log.warn("could not enqueue OpenRouter provisioning for a newly funded "
                            + "llm key; the sweep will pick it up", e);
                }
            });
        }
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
        writeSuspend(actor, key, reason, null, ip);
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
        writeResume(actor, key, null, ip);
        return get(actor, keyId);
    }

    /**
     * Contract op {@code updateAdminLlmKeyExpiry}: moves when the key stops
     * working, the same way an approval sets it.
     *
     * <p>A key that has already lapsed may be given a future date and works
     * again from that moment; the gateway reads the expiry off the document,
     * so the generation is bumped like every other document write.
     *
     * <p>A key with an OpenRouter half may only be shortened (operator,
     * 2026-09-28). The vendor fixes a key's expiry at creation and its update
     * call has no expiry field, so extending ours alone would leave the paid
     * models dying at the old date while the platform says the key is live;
     * shortening is harmless because the gateway enforces the platform expiry
     * itself and the vendor key merely outlives it unused. Nothing on the
     * provisioning side reacts: the sweep only picks keys that have no vendor
     * key yet, and the reconciler reads the expiry only to disable a lapsed
     * key, which is the right answer to a shortened one.
     */
    @Transactional
    public AdminLlmKeyDetailResponse updateExpiry(AuthenticatedUser actor, UUID keyId,
            LocalDate endDate, String ip) {
        Instant newExpiresAt = expiresAtFor(endDate, clock);
        LlmApiKey key = requireWritable(actor, keyId);
        generations.bump();
        entityManager.refresh(key);
        ApiException refusal = expiryRefusal(key, newExpiresAt);
        if (refusal != null) {
            throw refusal;
        }
        writeExpiry(actor, key, newExpiresAt, null, ip);
        return get(actor, keyId);
    }

    /**
     * The instant a key granted through {@code endDate} stops working: the
     * start of the following day in KST, so the end date is inclusive. The
     * same arithmetic approval uses; a date before today is refused because
     * an expiry in the past is a revoke wearing the wrong name.
     */
    public static Instant expiresAtFor(LocalDate endDate, Clock clock) {
        if (endDate.isBefore(ClockConfig.todayKst(clock))) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("endDate",
                    "종료일은 오늘(KST) 이후여야 합니다.")));
        }
        return endDate.plusDays(1).atStartOfDay(ClockConfig.KST).toInstant();
    }

    /** Why this key's expiry may not move to {@code newExpiresAt}, or null when it may. */
    public static @Nullable ApiException expiryRefusal(LlmApiKey key, Instant newExpiresAt) {
        if (key.getStatus() == LlmApiKeyStatus.REVOKED) {
            return invalidState("폐기된 키의 만료일은 바꿀 수 없습니다.");
        }
        if (extendsProvisionedKey(key, newExpiresAt)) {
            return new ApiException(HttpStatus.CONFLICT, ErrorCodes.LLM_KEY_INVALID_STATE,
                    "키 상태가 올바르지 않습니다",
                    "유료 모델 키의 만료 연장은 아직 지원하지 않습니다. 공급자가 키의 만료일을 발급 시점에 "
                            + "고정하기 때문이며, 앞당기는 것은 가능합니다.");
        }
        return null;
    }

    /** True when the key has a vendor half and the new date is later than the one it has. */
    public static boolean extendsProvisionedKey(LlmApiKey key, Instant newExpiresAt) {
        return key.getOpenrouterKeyHash() != null && key.getExpiresAt() != null
                && newExpiresAt.isAfter(key.getExpiresAt());
    }

    /** Lands a new expiry on a key the caller has locked and cleared. */
    public void writeExpiry(AuthenticatedUser actor, LlmApiKey key, Instant newExpiresAt,
            @Nullable UUID batchId, String ip) {
        Instant old = key.getExpiresAt();
        key.changeExpiry(newExpiresAt, Instant.now());
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("expiresAt", old == null ? null : old.toString());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("old", before);
        args.put("new", Map.of("expiresAt", newExpiresAt.toString()));
        if (batchId != null) {
            args.put("batchId", batchId);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.LLM_KEY_EXPIRY_UPDATE, "llm_key", key.getPublicId(), args, ip);
        if (key.getOpenrouterKeyHash() == null && key.getCreditLimit().signum() > 0) {
            // A funded key whose vendor half is still to come. A provisioning
            // attempt in flight captured the old expiry and will strand its
            // key on seeing this one, and the sweep would only return in a
            // few minutes; queue the attempt now, as the limits path does.
            long internalKeyId = key.getId();
            afterCommit("enqueue openrouter provision " + key.getPublicId(), () -> {
                try {
                    jobScheduler.enqueue(() -> provisioner.provision(internalKeyId));
                } catch (RuntimeException e) {
                    log.warn("could not enqueue OpenRouter provisioning after an expiry change; "
                            + "the sweep will pick it up", e);
                }
            });
        }
    }

    /** Suspends a key the caller has locked and found ACTIVE. */
    public void writeSuspend(AuthenticatedUser actor, LlmApiKey key, String reason,
            @Nullable UUID batchId, String ip) {
        LlmApiKeyStatus old = key.getStatus();
        key.suspend(Instant.now());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("reason", reason.strip());
        args.put("old", Map.of("status", old.name()));
        args.put("new", Map.of("status", key.getStatus().name()));
        if (batchId != null) {
            args.put("batchId", batchId);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.LLM_KEY_SUSPEND, "llm_key", key.getPublicId(), args, ip);
        if (key.getOpenrouterKeyHash() != null) {
            String hash = key.getOpenrouterKeyHash();
            long internalKeyId = key.getId();
            afterCommit("openrouter status change " + key.getPublicId(),
                    () -> provisioner.setDisabledAfterStatusChange(
                    internalKeyId, hash, true));
        }
    }

    /** Resumes a key the caller has locked and found SUSPENDED. */
    public void writeResume(AuthenticatedUser actor, LlmApiKey key, @Nullable UUID batchId,
            String ip) {
        LlmApiKeyStatus old = key.getStatus();
        key.resume(Instant.now());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("old", Map.of("status", old.name()));
        args.put("new", Map.of("status", key.getStatus().name()));
        if (batchId != null) {
            args.put("batchId", batchId);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.LLM_KEY_RESUME, "llm_key", key.getPublicId(), args, ip);
        if (key.getOpenrouterKeyHash() != null) {
            String hash = key.getOpenrouterKeyHash();
            long internalKeyId = key.getId();
            afterCommit("openrouter status change " + key.getPublicId(),
                    () -> provisioner.setDisabledAfterStatusChange(
                    internalKeyId, hash, false));
        }
    }

    /**
     * Revokes a key the caller has locked, keeping its row, as the holder's
     * own revoke does. The money axis must die with the key and must not wait
     * for the gateway's next poll, so the OpenRouter half is deleted after
     * commit; failures there are the reconciler's to catch.
     */
    public void writeRevoke(AuthenticatedUser actor, LlmApiKey key, @Nullable UUID batchId,
            String ip) {
        LlmApiKeyStatus old = key.getStatus();
        key.revoke(Instant.now());
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("old", Map.of("status", old.name()));
        args.put("new", Map.of("status", key.getStatus().name()));
        if (batchId != null) {
            args.put("batchId", batchId);
        }
        auditService.recordAfterCommit(actor.id(), actor.role().name(),
                AuditService.LLM_KEY_REVOKE, "llm_key", key.getPublicId(), args, ip);
        String openrouterKeyHash = key.getOpenrouterKeyHash();
        if (openrouterKeyHash != null) {
            long internalKeyId = key.getId();
            afterCommit("openrouter delete " + key.getPublicId(),
                    () -> provisioner.deleteAfterRevoke(internalKeyId, openrouterKeyHash));
        }
    }

    /**
     * The models this key may call, for the screen that decides its allow list.
     *
     * <p>The same answer the key's holder gets, computed by the same service.
     * A reviewer choosing what to permit and a holder reading what they have
     * are looking at one fact, and two computations of it would drift into two
     * answers about a fence.
     *
     * <p>The institution scoping is this surface's own: an org-tier reviewer
     * sees keys of the institutions they read, and a system-tier one sees all.
     * Resource grants do not enter it, which is exactly why this is a separate
     * path rather than a widening of the holder's.
     */
    @Transactional(readOnly = true)
    public LlmKeyModelsResponse models(AuthenticatedUser actor, UUID keyId) {
        return modelService.of(requireReadable(actor, keyId));
    }

    /**
     * One key's usage for an administrator (contract op
     * {@code getAdminLlmKeyUsage}).
     *
     * <p>This opens a line the holder's side draws deliberately. There, usage
     * is content rather than standing: a workspace owner who can see that a key
     * exists still cannot read what it was used for. Here an administrator sees
     * it, on an operator decision of 2026-09-06, because the same person sets
     * this key's limits and carries what it spends, and a limit decided without
     * the usage behind it is a guess.
     *
     * <p>The scoping is this surface's own, the same as the detail beside it:
     * an org-tier reviewer sees keys of the institutions they read. Resource
     * grants do not enter it.
     *
     * <p>The trend is the holder's own response, not a second query. The
     * breakdowns beside it are the part an administrator has and the holder
     * does not.
     */
    @Transactional(readOnly = true)
    public AdminLlmKeyUsageResponse usage(AuthenticatedUser actor, UUID keyId, int days) {
        LlmApiKey key = requireReadable(actor, keyId);
        LlmKeyUsageTrendResponse trend = keyUsageService.trendOf(key, days);
        return new AdminLlmKeyUsageResponse(trend,
                keyUsageService.costPoints(key.getId(), trend.from(), trend.to()),
                keyUsageService.endpointKinds(key.getId(), trend.from(), trend.to()),
                keyUsageService.servedModels(key.getId(), trend.from(), trend.to()));
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
        if (!isMutable(status)) {
            throw invalidState(status + " 상태의 키는 " + action + " 수 없습니다.");
        }
    }

    /** The states whose limits may still be edited: anything not yet lapsed or revoked. */
    public static boolean isMutable(LlmApiKeyStatus status) {
        return status == LlmApiKeyStatus.PENDING || status == LlmApiKeyStatus.ACTIVE
                || status == LlmApiKeyStatus.SUSPENDED;
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

    private static void afterCommit(String label, Runnable action) {
        AfterCommit.run(label, action);
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

    private OpenRouterAccount resolveLimitAccount(LlmApiKey key, @Nullable UUID requested,
            BigDecimal creditLimit) {
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
        if (firstBindingEligible(key)) {
            return accountSelection.select(key.getOrgId(), creditLimit, requested);
        }
        if (requested != null) {
            throw immutableBinding();
        }
        // The same key cannot fund money either, and saying so here is the
        // difference between a defined refusal and a constraint violation
        // surfacing as a 500: the write below would set a positive limit on a
        // row that names no account.
        if (creditLimit.signum() > 0) {
            throw immutableBinding();
        }
        return null;
    }

    /**
     * The account a limits write on this key would settle on, answered without
     * throwing and without locking: the bulk change judges two hundred keys
     * inside one transaction, where an exception thrown through a transactional
     * proxy would mark the whole batch for rollback, and it judges before it
     * has taken the generation, which is where every account lock must queue.
     * The write step locks the account it was handed ({@link #lockAccount}).
     *
     * <p>Never asks for a specific account. Binding is not part of the bulk
     * change, so an unbound key takes the institution's one eligible account
     * exactly as a single write with no account named would.
     */
    public AccountResolution resolveAccountQuietly(LlmApiKey key, BigDecimal creditLimit) {
        if (key.getOpenrouterAccountId() != null) {
            OpenRouterAccount current = accountRepository.findById(key.getOpenrouterAccountId())
                    .orElse(null);
            return current == null
                    ? AccountResolution.refused(new FieldValidationError("openrouterAccountId",
                            "연결된 사업 계정을 찾을 수 없습니다."))
                    : AccountResolution.of(current);
        }
        if (firstBindingEligible(key)) {
            List<FieldValidationError> errors = new ArrayList<>();
            OpenRouterAccount account = accountSelection.peekDefault(key.getOrgId(),
                    creditLimit, errors);
            return errors.isEmpty() ? AccountResolution.of(account)
                    : AccountResolution.refused(errors.getFirst());
        }
        if (creditLimit.signum() > 0) {
            return AccountResolution.refused(new FieldValidationError("creditLimit",
                    "사업 계정 없이 발급된 키에는 금액 한도를 둘 수 없습니다."));
        }
        return AccountResolution.of(null);
    }

    /**
     * Locks the account a judgment settled on, re-read under the lock, and
     * says whether it is still eligible. Null when it no longer is, which the
     * caller reports rather than binding to.
     */
    public @Nullable OpenRouterAccount lockAccount(OpenRouterAccount account) {
        OpenRouterAccount locked = accountRepository.findWithLockById(account.getId()).orElse(null);
        return locked != null && accountSelection.eligible(locked) ? locked : null;
    }

    /** What {@link #resolveAccountQuietly} settled on: an account (possibly none) or a refusal. */
    public record AccountResolution(@Nullable OpenRouterAccount account,
            @Nullable FieldValidationError refusal) {

        static AccountResolution of(@Nullable OpenRouterAccount account) {
            return new AccountResolution(account, null);
        }

        static AccountResolution refused(FieldValidationError refusal) {
            return new AccountResolution(null, refusal);
        }

        public boolean accepted() {
            return refusal == null;
        }
    }

    private static boolean firstBindingEligible(LlmApiKey key) {
        return key.getCreditLimit().signum() <= 0
                && key.getOpenrouterKeyHash() == null
                && key.getOpenrouterKeyEnc() == null;
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
