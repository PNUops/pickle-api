package kr.ac.pusan.pickle.llm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.llm.dto.ApproveLlmKeyRequestSpec;
import kr.ac.pusan.pickle.llm.dto.CreateLlmKeyRequestSpec;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccount;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccountSelectionService;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAllocationQuery;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestTypeHandler;
import kr.ac.pusan.pickle.request.dto.CreateRequestRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
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

    private final LlmKeyRequestDetailRepository detailRepository;
    private final LlmApiKeyRepository keyRepository;
    private final LlmGatewayGenerations generations;
    private final OpenRouterAccountSelectionService accountSelection;
    private final OpenRouterAllocationQuery allocationQuery;
    private final ObjectMapper objectMapper;

    public LlmKeyRequestSupport(LlmKeyRequestDetailRepository detailRepository,
            LlmApiKeyRepository keyRepository, LlmGatewayGenerations generations,
            OpenRouterAccountSelectionService accountSelection,
            OpenRouterAllocationQuery allocationQuery, ObjectMapper objectMapper) {
        this.detailRepository = detailRepository;
        this.keyRepository = keyRepository;
        this.generations = generations;
        this.accountSelection = accountSelection;
        this.allocationQuery = allocationQuery;
        this.objectMapper = objectMapper;
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
        List<String> models = CreditModelAllowlist.normalize(spec.grantedCreditAllowedModels(),
                "llmKey.grantedCreditAllowedModels", errors);
        if (!models.isEmpty()
                && (spec.grantedCreditLimit() == null
                        || spec.grantedCreditLimit().signum() <= 0)) {
            errors.add(new FieldValidationError("llmKey.grantedCreditLimit",
                    "모델 허용 목록을 두려면 0보다 큰 금액 한도가 필요합니다."));
        }
    }

    @Override
    public Materialized materialize(Request request, ApproveRequestRequest form,
            AuthenticatedUser actor) {
        ApproveLlmKeyRequestSpec spec = form.llmKey();

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
        // skipped. (The account row itself is only locked when the approver
        // named an account; auto-selection does not lock it.)
        Map<String, Object> allocationRecord = account == null ? Map.of()
                : allocationQuery.grantRecord(account.getId(), spec.grantedCreditLimit());

        LlmKeyRequestDetail detail = detailRepository.findByRequestId(request.getId()).orElseThrow();
        // Re-normalized rather than carried from validation: this is the value
        // that gets stored, and reading it from the same function both times is
        // what keeps the checked list and the saved list the same list.
        // validateApprove has already refused anything malformed, so the error
        // sink here stays empty.
        String creditAllowedModels = CreditModelAllowlist.toJson(objectMapper,
                CreditModelAllowlist.normalize(spec.grantedCreditAllowedModels(),
                        "llmKey.grantedCreditAllowedModels", new ArrayList<>()));
        detail.grant(spec.grantedRpm(), spec.grantedTpm(), spec.grantedConcurrency(),
                spec.grantedDailyTokens(), spec.grantedCreditLimit(),
                spec.grantedCreditLimitReset(), account == null ? null : account.getId(),
                creditAllowedModels);

        // The key lands PENDING, so nothing servable changes yet, but it still
        // follows the same generation-before-document-write discipline.
        LlmApiKey key = new LlmApiKey(request.getWorkspaceId(),
                request.getOrgId(), request.getId(), request.getDisplayName(),
                // 발급되는 키의 용도는 신청서의 사용 목적이다. 종류마다 용도를 따로
                // 묻던 칸을 없앴다 — 같은 질문을 연달아 두 번 하고 있었다.
                request.getPurpose(),
                form.grantedEndDate() == null ? null
                        : form.grantedEndDate().plusDays(1).atStartOfDay(
                                java.time.ZoneId.of("Asia/Seoul")).toInstant(),
                spec.grantedRpm(), spec.grantedTpm(), spec.grantedConcurrency(),
                spec.grantedDailyTokens(), spec.grantedCreditLimit(),
                spec.grantedCreditLimitReset(), account == null ? null : account.getId(),
                request.getRequesterId());
        // Set before the insert, not after it. Setting it afterwards left the
        // value to dirty checking, so the row the gateway serves came out empty
        // — unrestricted — wherever the flush did not happen to follow. Every
        // other surface reads the reviewer's decision from the request detail
        // and looked correct, which is what made it quiet.
        key.applyCreditAllowedModels(creditAllowedModels);
        key = keyRepository.save(key);

        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("llmKeyId", key.getPublicId());
        auditArgs.put("grantedRpm", spec.grantedRpm());
        auditArgs.put("grantedTpm", spec.grantedTpm());
        auditArgs.put("grantedConcurrency", spec.grantedConcurrency());
        auditArgs.put("grantedDailyTokens", spec.grantedDailyTokens());
        auditArgs.put("grantedCreditLimit", spec.grantedCreditLimit());
        auditArgs.put("grantedCreditLimitReset", spec.grantedCreditLimitReset());
        // The resolved list, not what the form sent: an approval prefilled from
        // an account default has to leave behind what was actually granted,
        // because the default it came from can change later.
        auditArgs.put("grantedCreditAllowedModels",
                CreditModelAllowlist.fromJson(objectMapper, creditAllowedModels));
        auditArgs.put("openrouterAccountId", account == null ? null : account.getPublicId());
        auditArgs.putAll(allocationRecord);
        return new Materialized(key.getId(), key.getName(), auditArgs, () -> {
        });
    }
}
