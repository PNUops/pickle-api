package kr.ac.pusan.pickle.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.ApproveRequestRequest;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.common.text.Texts;
import kr.ac.pusan.pickle.llm.dto.ApproveLlmKeyRequestSpec;
import kr.ac.pusan.pickle.llm.dto.CreateLlmKeyRequestSpec;
import kr.ac.pusan.pickle.request.Request;
import kr.ac.pusan.pickle.request.RequestTypeHandler;
import kr.ac.pusan.pickle.request.dto.CreateRequestRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.springframework.stereotype.Component;

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

    public LlmKeyRequestSupport(LlmKeyRequestDetailRepository detailRepository,
            LlmApiKeyRepository keyRepository, LlmGatewayGenerations generations) {
        this.detailRepository = detailRepository;
        this.keyRepository = keyRepository;
        this.generations = generations;
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
    }

    @Override
    public void saveDetail(Request request, CreateRequestRequest form) {
        CreateLlmKeyRequestSpec spec = form.llmKey();
        detailRepository.save(new LlmKeyRequestDetail(request.getId(),
                Texts.blankToNull(spec.usagePlan()), spec.reqRpm(), spec.reqTpm(),
                spec.reqDailyTokens()));
    }

    @Override
    public Map<String, Object> submitAuditArgs(Request request) {
        Map<String, Object> args = new LinkedHashMap<>();
        detailRepository.findByRequestId(request.getId()).ifPresent(detail -> {
            args.put("reqRpm", detail.getReqRpm());
            args.put("reqTpm", detail.getReqTpm());
            args.put("reqDailyTokens", detail.getReqDailyTokens());
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
    }

    @Override
    public Materialized materialize(Request request, ApproveRequestRequest form,
            AuthenticatedUser actor) {
        ApproveLlmKeyRequestSpec spec = form.llmKey();
        LlmKeyRequestDetail detail = detailRepository.findByRequestId(request.getId()).orElseThrow();
        detail.grant(spec.grantedRpm(), spec.grantedTpm(), spec.grantedConcurrency(),
                spec.grantedDailyTokens());

        // Bump before the write, in this transaction: the row lock is what makes
        // commit order and generation order agree. The key lands PENDING, so
        // nothing servable changes yet — but the discipline has no exceptions,
        // because an exception is what a later reader would copy.
        generations.bump();
        LlmApiKey key = keyRepository.save(new LlmApiKey(request.getWorkspaceId(),
                request.getOrgId(), request.getId(), request.getDisplayName(),
                detail.getReqPurpose(),
                form.grantedEndDate() == null ? null
                        : form.grantedEndDate().plusDays(1).atStartOfDay(
                                java.time.ZoneId.of("Asia/Seoul")).toInstant(),
                spec.grantedRpm(), spec.grantedTpm(), spec.grantedConcurrency(),
                spec.grantedDailyTokens(), request.getRequesterId()));

        Map<String, Object> auditArgs = new LinkedHashMap<>();
        auditArgs.put("llmKeyId", key.getPublicId());
        auditArgs.put("grantedRpm", spec.grantedRpm());
        auditArgs.put("grantedTpm", spec.grantedTpm());
        auditArgs.put("grantedConcurrency", spec.grantedConcurrency());
        auditArgs.put("grantedDailyTokens", spec.grantedDailyTokens());
        return new Materialized(key.getId(), key.getName(), auditArgs, () -> {
        });
    }
}
