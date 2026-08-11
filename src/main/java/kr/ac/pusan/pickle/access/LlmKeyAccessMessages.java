package kr.ac.pusan.pickle.access;

import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ErrorCodes;

/**
 * Every sentence the access machinery says about an LLM API key, and the names
 * its access-list edits take in the audit trail.
 *
 * <p>Interim home. The resource-generic machinery expects these from the
 * type's {@code ResourceTypeAdapter} ({@code accessMessages()} /
 * {@code accessAudit()}), the way the VM's live on
 * {@link kr.ac.pusan.pickle.resource.VmResourceAdapter}; when the LLM key's
 * adapter lands, these constants move onto it and this class goes away. Until
 * then this is their only home, so the key's own services and the access
 * controller refuse in one wording rather than two that drift.
 */
public final class LlmKeyAccessMessages {

    public static final ResourceAccessMessages MESSAGES = new ResourceAccessMessages(
            "해당 LLM API 키가 존재하지 않습니다.",
            new ResourceAccessMessages.Refusal("이 키에 접근할 권한이 없습니다",
                    "이 LLM API 키의 접근 목록에 등록되어 있지 않습니다. 자원 소유자에게 접근 권한을 요청해 주세요."),
            new ResourceAccessMessages.Refusal("접근 권한을 관리할 권한이 없습니다",
                    "이 LLM API 키의 소유자 또는 워크스페이스 소유자만 접근 권한을 관리할 수 있습니다."),
            "이 키를 소유한 워크스페이스의 구성원만 접근 권한을 받을 수 있습니다. 먼저 워크스페이스에 추가해 주세요.",
            ErrorCodes.LLM_KEY_ACCESS_GRANT_EXISTS,
            new ResourceAccessMessages.Refusal("이미 접근 권한이 있습니다",
                    "이 대상은 이미 이 LLM API 키의 접근 목록에 있습니다. 등급을 바꾸려면 기존 항목을 수정해 주세요."));

    public static final ResourceAccessAudit AUDIT = new ResourceAccessAudit("llm_key",
            AuditService.LLM_KEY_ACCESS_GRANT_ADD, AuditService.LLM_KEY_ACCESS_GRANT_UPDATE,
            AuditService.LLM_KEY_ACCESS_GRANT_REMOVE, AuditService.LLM_KEY_ACCESS_BREAK_GLASS);

    private LlmKeyAccessMessages() {
    }
}
