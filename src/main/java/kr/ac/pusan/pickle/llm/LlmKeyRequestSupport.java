package kr.ac.pusan.pickle.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.llm.dto.ApproveLlmKeyRequestSpec;
import kr.ac.pusan.pickle.llm.dto.CreateLlmKeyRequestSpec;
import kr.ac.pusan.pickle.llm.openrouter.LlmOpenRouterProvisioner;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccount;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountRepository;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountSelectionService;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAllocationQuery;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestTypeHandler;
import kr.ac.pusan.pickle.request.dto.CreateRequestRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jobrunr.scheduling.JobScheduler;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Everything about a request that is particular to LLM API keys.
 *
 * <p>The shape differs from the VM's in one way worth stating: approval does
 * not produce a usable resource. It produces a key with no secret. Only the
 * key's owner may ever see a plaintext, and the approver is not there when
 * they do, so the owner mints it themselves — see {@link LlmApiKeyService}.
 * What approval settles is that they may have one, and on what limits.
 */
@Component
public class LlmKeyRequestSupport implements RequestTypeHandler {

    private static final Logger log = LoggerFactory.getLogger(LlmKeyRequestSupport.class);

    private final LlmKeyRequestDetailRepository detailRepository;
    private final LlmApiKeyRepository keyRepository;
    private final LlmGatewayGenerations generations;
    private final OpenRouterAccountSelectionService accountSelection;
    private final OpenRouterAllocationQuery allocationQuery;
    private final ObjectMapper objectMapper;
    private final LlmOpenRouterProvisioner provisioner;
    private final JobScheduler jobScheduler;
    private final OpenRouterAccountRepository accountRepository;

    public LlmKeyRequestSupport(LlmKeyRequestDetailRepository detailRepository,
            LlmApiKeyRepository keyRepository, LlmGatewayGenerations generations,
            OpenRouterAccountSelectionService accountSelection,
            OpenRouterAllocationQuery allocationQuery, ObjectMapper objectMapper,
            LlmOpenRouterProvisioner provisioner, JobScheduler jobScheduler,
            OpenRouterAccountRepository accountRepository) {
        this.accountRepository = accountRepository;
        this.detailRepository = detailRepository;
        this.keyRepository = keyRepository;
        this.generations = generations;
        this.accountSelection = accountSelection;
        this.allocationQuery = allocationQuery;
        this.objectMapper = objectMapper;
        this.provisioner = provisioner;
        this.jobScheduler = jobScheduler;
    }

    @Override
    public ResourceType type() {
        return ResourceType.LLM_API_KEY;
    }

    @Override
    public void validateCreate(CreateRequestRequest form, List<FieldValidationError> errors) {
        CreateLlmKeyRequestSpec spec = form.llmKey();
        if (spec == null) {
            errors.add(new FieldValidationError("llmKey", "LLM API 키 신청 항목(llmKey)을 입력해 주세요."));
            return;
        }
        // Every limit is optional: a request that names none is asking for the
        // service defaults, which is what most of them are. What is checked is
        // only that a number somebody did write is a number that can be granted.
        if (spec.reqRpm() != null && spec.reqTpm() != null && spec.reqTpm() < spec.reqRpm()) {
            errors.add(new FieldValidationError("llmKey.reqTpm",
                    "분당 토큰 수는 분당 요청 수보다 작을 수 없습니다."));
        }
        // 축은 다르다. 한도와 달리 비워 둔 것이 "기본값"을 뜻하지 않으므로, 무엇을
        // 쓰겠다는 것인지 하나는 말해야 한다.
        if (!useCampus(spec) && !useCommercial(spec)) {
            errors.add(new FieldValidationError("llmKey.useCampusModels",
                    "Pickle LLM과 유료 모델 중 최소 하나는 선택해 주세요."));
        }
        if (spec.reqCreditLimit() != null && !useCommercial(spec)) {
            errors.add(new FieldValidationError("llmKey.reqCreditLimit",
                    "유료 모델을 쓰지 않는 신청에는 금액을 적을 수 없습니다."));
        }
    }

    /** 비우면 쓰는 것으로 본다. 자체 서빙 모델만 쓰는 신청이 보통이다. */
    private static boolean useCampus(CreateLlmKeyRequestSpec spec) {
        return !Boolean.FALSE.equals(spec.useCampusModels());
    }

    /** 비우면 쓰지 않는 것으로 본다. 돈이 드는 축은 명시적으로 켠다. */
    private static boolean useCommercial(CreateLlmKeyRequestSpec spec) {
        return Boolean.TRUE.equals(spec.useCommercialModels());
    }

    @Override
    public void saveDetail(Request request, CreateRequestRequest form) {
        CreateLlmKeyRequestSpec spec = form.llmKey();
        detailRepository.save(new LlmKeyRequestDetail(request.getId(),
                spec.reqRpm(), spec.reqTpm(),
                spec.reqDailyTokens(), useCampus(spec), useCommercial(spec),
                spec.reqCreditLimit()));
    }

    @Override
    public Map<String, Object> submitAuditArgs(Request request) {
        Map<String, Object> args = new LinkedHashMap<>();
        detailRepository.findByRequestId(request.getId()).ifPresent(detail -> {
            args.put("reqRpm", detail.getReqRpm());
            args.put("reqTpm", detail.getReqTpm());
            args.put("reqDailyTokens", detail.getReqDailyTokens());
            args.put("reqUseCampus", detail.isReqUseCampus());
            args.put("reqUseCommercial", detail.isReqUseCommercial());
        });
        return args;
    }

    @Override
    public void validateApprove(Request request, ApproveRequestRequest form,
            List<FieldValidationError> errors) {
        ApproveLlmKeyRequestSpec spec = form.llmKey();
        if (spec == null) {
            // Unlike a VM, approving with nothing filled in is the ordinary
            // decision: it grants the service defaults. The member has to be
            // present so the reviewer's intent is explicit, but it may be empty.
            errors.add(new FieldValidationError("llmKey",
                    "LLM API 키 승인 항목(llmKey)을 입력해 주세요. 기본 한도로 승인하려면 빈 객체를 보내주세요."));
            return;
        }
        if (spec.grantedRpm() != null && spec.grantedTpm() != null
                && spec.grantedTpm() < spec.grantedRpm()) {
            errors.add(new FieldValidationError("llmKey.grantedTpm",
                    "분당 토큰 수는 분당 요청 수보다 작을 수 없습니다."));
        }
        // A reset window without a positive limit renews nothing; the reviewer
        // almost certainly forgot the amount, so say it rather than store it.
        if (spec.grantedCreditLimitReset() != null
                && (spec.grantedCreditLimit() == null
                        || spec.grantedCreditLimit().signum() <= 0)) {
            errors.add(new FieldValidationError("llmKey.grantedCreditLimit",
                    "리셋 창을 두려면 0보다 큰 금액 한도가 필요합니다."));
        }
        // Same shape, same reason: a model allow list on a key with no money
        // restricts nothing, because there is nothing on the money axis to
        // restrict. Saying so beats storing a decision that does not apply.
        List<String> models = CreditModelPatterns.normalize(spec.grantedCreditAllowedModels(),
                "llmKey.grantedCreditAllowedModels", errors);
        if (!models.isEmpty()
                && (spec.grantedCreditLimit() == null
                        || spec.grantedCreditLimit().signum() <= 0)) {
            errors.add(new FieldValidationError("llmKey.grantedCreditLimit",
                    "모델 허용 목록을 두려면 0보다 큰 금액 한도가 필요합니다."));
        }
        // The deny list is checked for format and nothing else. It carries no
        // "needs money" rule, because a refusal is true at an amount of zero and
        // stays true when somebody funds the key later without reopening this
        // form — the shape above would throw the reviewer's refusal away at the
        // one moment it costs nothing to keep.
        CreditModelPatterns.normalize(spec.grantedCreditDeniedModels(),
                "llmKey.grantedCreditDeniedModels", errors);
        // Format only, and deliberately no "needs money" rule either, but for a
        // different reason than the deny list above. This list grants rather
        // than restricts, so an entry with no money behind it opens a path the
        // key cannot pay to use — which already fails closed at the credential
        // check. Refusing it here would instead make the column impossible to
        // fill before a key is funded.
        PassthroughEndpoints.normalize(spec.grantedPassthroughEndpoints(),
                "llmKey.grantedPassthroughEndpoints", errors);
    }

    @Override
    public Materialized materialize(Request request, ApproveRequestRequest form,
            AuthenticatedUser actor) {
        ApproveLlmKeyRequestSpec spec = form.llmKey();
        Recorded recorded = record(request, spec, spec.grantedCreditLimit());
        return issueKey(request, form.grantedEndDate(), spec.grantedRpm(), spec.grantedTpm(),
                spec.grantedConcurrency(), spec.grantedDailyTokens(), spec.grantedCreditLimit(),
                spec.grantedCreditLimitReset(), recorded.accountId(), recorded.accountPublicId(),
                recorded.allocationRecord(), recorded.creditAllowedModels(),
                recorded.creditDeniedModels(), recorded.passthroughEndpoints(),
                request.getRequesterId());
    }

    @Override
    public boolean supportsRecipients() {
        return true;
    }

    /**
     * The decision half of {@link #materialize}: the limits, the lists and the
     * account, stored once on the request. The account is chosen here, while
     * the approver is looking, so every recipient's key bills the same one.
     */
    @Override
    public Map<String, Object> recordGrant(Request request, ApproveRequestRequest form,
            int resourceCount) {
        ApproveLlmKeyRequestSpec spec = form.llmKey();
        // Every key this grant will make carries the full limit, so the
        // account's commitment grows by the limit once per key, and that is the
        // figure its over-allocation record has to be measured with. Warned
        // about in the audit exactly as a single approval is, never refused.
        java.math.BigDecimal delta = spec.grantedCreditLimit() == null ? null
                : spec.grantedCreditLimit().multiply(java.math.BigDecimal.valueOf(resourceCount));
        Recorded recorded = record(request, spec, delta);
        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("grantedCreditLimit", spec.grantedCreditLimit());
        auditArgs.put("keysExpected", resourceCount);
        auditArgs.put("openrouterAccountId", recorded.accountPublicId());
        auditArgs.putAll(recorded.allocationRecord());
        return auditArgs;
    }

    /**
     * One recipient's key, from the stored grant. Takes the gateway generation
     * lock first, as every key write does, and only then reads the account's
     * standing commitment for the audit record.
     */
    @Override
    public Materialized createFor(Request request, kr.ac.pusan.pickle.request.RequestReview review,
            long ownerId, Set<Long> excludedNodeIds) {
        generations.bump();
        LlmKeyRequestDetail detail = detailRepository.findByRequestId(request.getId()).orElseThrow();
        Long accountId = detail.getGrantedOpenrouterAccountId();
        OpenRouterAccount account = accountId == null ? null
                : accountRepository.findById(accountId).orElseThrow();
        Map<String, Object> allocationRecord = account == null ? Map.of()
                : allocationQuery.grantRecord(account.getId(), detail.getGrantedCreditLimit());
        return issueKey(request, review.getGrantedEndDate(), detail.getGrantedRpm(),
                detail.getGrantedTpm(), detail.getGrantedConcurrency(),
                detail.getGrantedDailyTokens(), detail.getGrantedCreditLimit(),
                detail.getGrantedCreditLimitReset(), accountId,
                account == null ? null : account.getPublicId(), allocationRecord,
                detail.getGrantedCreditAllowedModels(), detail.getGrantedCreditDeniedModels(),
                detail.getGrantedPassthroughEndpoints(), ownerId);
    }

    /** What recording a grant settled, for the key that may follow it in the same transaction. */
    private record Recorded(@Nullable Long accountId, @Nullable UUID accountPublicId,
            Map<String, Object> allocationRecord, String creditAllowedModels,
            String creditDeniedModels, String passthroughEndpoints) {
    }

    private Recorded record(Request request, ApproveLlmKeyRequestSpec spec,
            java.math.@Nullable BigDecimal commitmentDelta) {
        // The generation row is the global lock for every gateway-document
        // write. Take it before the account row, matching first limits binding,
        // so concurrent approval and limits replacement cannot deadlock.
        generations.bump();
        OpenRouterAccount account = accountSelection.select(request.getOrgId(),
                spec.grantedCreditLimit(), spec.openrouterAccountId());
        // Read the account's standing commitment before the new key is written,
        // so the record says what was still outstanding when this decision was
        // made. After the generation bump, never before: that row lock is the
        // serialization point, and a read in front of it sees the queue it
        // skipped. (The account rows are locked either way: a named account
        // by its lookup, auto-selection through the listing it chooses from.)
        Map<String, Object> allocationRecord = account == null ? Map.of()
                : allocationQuery.grantRecord(account.getId(), commitmentDelta);

        LlmKeyRequestDetail detail = detailRepository.findByRequestId(request.getId()).orElseThrow();
        // Re-normalized rather than carried from validation: this is the value
        // that gets stored, and reading it from the same function both times is
        // what keeps the checked list and the saved list the same list.
        // validateApprove has already refused anything malformed, so the error
        // sink here stays empty.
        String creditAllowedModels = CreditModelPatterns.toJson(objectMapper,
                CreditModelPatterns.normalize(spec.grantedCreditAllowedModels(),
                        "llmKey.grantedCreditAllowedModels", new ArrayList<>()));
        String creditDeniedModels = CreditModelPatterns.toJson(objectMapper,
                CreditModelPatterns.normalize(spec.grantedCreditDeniedModels(),
                        "llmKey.grantedCreditDeniedModels", new ArrayList<>()));
        String passthroughEndpoints = PassthroughEndpoints.toJson(objectMapper,
                PassthroughEndpoints.normalize(spec.grantedPassthroughEndpoints(),
                        "llmKey.grantedPassthroughEndpoints", new ArrayList<>()));
        detail.grant(spec.grantedRpm(), spec.grantedTpm(), spec.grantedConcurrency(),
                spec.grantedDailyTokens(), spec.grantedCreditLimit(),
                spec.grantedCreditLimitReset(), account == null ? null : account.getId(),
                creditAllowedModels, creditDeniedModels);
        detail.grantPassthroughEndpoints(passthroughEndpoints);
        return new Recorded(account == null ? null : account.getId(),
                account == null ? null : account.getPublicId(), allocationRecord,
                creditAllowedModels, creditDeniedModels, passthroughEndpoints);
    }

    /**
     * The resource half: one PENDING key owned by {@code ownerId}. The caller
     * holds the generation lock already.
     */
    private Materialized issueKey(Request request, java.time.@Nullable LocalDate grantedEndDate,
            @Nullable Integer rpm, @Nullable Integer tpm, @Nullable Integer concurrency,
            @Nullable Long dailyTokens, java.math.@Nullable BigDecimal creditLimit,
            @Nullable CreditLimitReset creditLimitReset, @Nullable Long accountId,
            @Nullable UUID accountPublicId, Map<String, Object> allocationRecord,
            String creditAllowedModels, String creditDeniedModels, String passthroughEndpoints,
            long ownerId) {
        // The key lands PENDING, so nothing servable changes yet, but it still
        // follows the same generation-before-document-write discipline.
        LlmApiKey key = new LlmApiKey(request.getWorkspaceId(),
                request.getOrgId(), request.getId(), request.getDisplayName(),
                // 발급되는 키의 용도는 신청서의 사용 목적이다. 종류마다 용도를 따로
                // 묻던 칸을 없앴다 — 같은 질문을 연달아 두 번 하고 있었다.
                request.getPurpose(),
                grantedEndDate == null ? null
                        : grantedEndDate.plusDays(1).atStartOfDay(
                                java.time.ZoneId.of("Asia/Seoul")).toInstant(),
                rpm, tpm, concurrency, dailyTokens, creditLimit, creditLimitReset, accountId,
                ownerId);
        // Set before the insert, not after it. Setting it afterwards left the
        // value to dirty checking, so the row the gateway serves came out empty
        // — unrestricted — wherever the flush did not happen to follow. Every
        // other surface reads the reviewer's decision from the request detail
        // and looked correct, which is what made it quiet.
        key.applyCreditModelLists(creditAllowedModels, creditDeniedModels);
        // Before the insert for the same reason. The failure mode here is the
        // mirror of the one above: a list that arrives after the flush leaves
        // the served row empty, which on this axis means the reviewer's grant
        // silently did not happen rather than a restriction silently lifting.
        key.applyPassthroughEndpoints(passthroughEndpoints);
        key = keyRepository.save(key);

        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("llmKeyId", key.getPublicId());
        auditArgs.put("grantedRpm", rpm);
        auditArgs.put("grantedTpm", tpm);
        auditArgs.put("grantedConcurrency", concurrency);
        auditArgs.put("grantedDailyTokens", dailyTokens);
        auditArgs.put("grantedCreditLimit", creditLimit);
        auditArgs.put("grantedCreditLimitReset", creditLimitReset);
        // The resolved list, not what the form sent: an approval prefilled from
        // an account default has to leave behind what was actually granted,
        // because the default it came from can change later.
        auditArgs.put("grantedCreditAllowedModels",
                CreditModelPatterns.fromJson(objectMapper, creditAllowedModels,
                        "llm key " + key.getPublicId()));
        auditArgs.put("grantedCreditDeniedModels",
                CreditModelPatterns.fromJson(objectMapper, creditDeniedModels,
                        "llm key " + key.getPublicId()));
        auditArgs.put("grantedPassthroughEndpoints",
                PassthroughEndpoints.fromJson(objectMapper, passthroughEndpoints,
                        "llm key " + key.getPublicId()));
        auditArgs.put("openrouterAccountId", accountPublicId);
        auditArgs.putAll(allocationRecord);
        // A money budget is useless until its OpenRouter key exists, and the
        // sweep that creates one runs every five minutes — long enough that a
        // student trying a commercial model straight after approval reads the
        // refusal as a fault in the platform. Ask for it now instead. The
        // sweep stays behind this as the retry, so the 2026-08-24 decision
        // that OpenRouter must never block issuance is intact: this runs only
        // after the approval has already committed.
        long keyId = key.getId();
        boolean funded = key.getCreditLimit().signum() > 0;
        // The approval notice links to the key rather than to the request:
        // issuing is the requester's next move and it lives on that screen,
        // and this is the only mail an LLM key ever sends.
        return new Materialized(key.getId(), key.getName(), auditArgs,
                () -> { if (funded) { provisionNow(keyId); } },
                Map.of("llmKeyId", key.getPublicId()));
    }

    /**
     * Enqueues the provisioning attempt, after commit. Durable rather than
     * inline for the reason the VM handler gives at the same seam — JobRunr's
     * storage provider commits on its own connection, so an in-transaction
     * enqueue can outlive a rollback — and because the remote call carries a
     * 30-second read timeout that has no business holding the approver's
     * response open.
     *
     * <p>Failures are swallowed deliberately. Losing this enqueue costs at
     * most one sweep interval, which is exactly where we were before it
     * existed; letting it escape would turn an approval that already
     * committed into a 500 and tell the approver their approval failed when
     * it did not.
     */
    private void provisionNow(long keyId) {
        try {
            jobScheduler.enqueue(() -> provisioner.provision(keyId));
        } catch (RuntimeException e) {
            log.warn("could not enqueue OpenRouter provisioning for the approved llm key; "
                    + "the sweep will pick it up", e);
        }
    }
}
