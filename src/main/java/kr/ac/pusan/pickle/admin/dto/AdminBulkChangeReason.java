package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Why one target of a bulk change was not changed (contract enum
 * {@code AdminBulkChangeReason}). The console shows a label per code and falls
 * back to the code itself for one it does not know, so the set stays small and
 * a new code is a contract change.
 */
@Schema(description = "대상 하나가 변경되지 않은 이유. "
        + "NOT_FOUND: 대상이 없거나 이 계정의 범위 밖입니다. "
        + "FORBIDDEN: 이 역할은 이 변경을 할 수 없습니다. "
        + "INVALID_STATE: 현재 상태에서는 할 수 없는 변경입니다. "
        + "EXPIRED: 사용 기간이 만료되어 먼저 기간을 연장해야 합니다. "
        + "PROTECTED: 삭제 보호가 켜져 있습니다. "
        + "INELIGIBLE: 대상이나 지정한 사용자가 이 변경의 대상이 될 수 없습니다. "
        + "NOT_MEMBER: 지정한 사용자가 소유 워크스페이스의 구성원이 아닙니다. "
        + "NO_GRANT: 지정한 사용자에게 바꾸거나 회수할 접근 권한이 없습니다. "
        + "ALREADY_GRANTED: 지정한 사용자가 이미 다른 등급의 접근 권한을 갖고 있습니다. "
        + "VALIDATION: 합쳐진 결과가 한도 규칙에 어긋납니다.")
public enum AdminBulkChangeReason {
    NOT_FOUND,
    FORBIDDEN,
    INVALID_STATE,
    EXPIRED,
    PROTECTED,
    INELIGIBLE,
    NOT_MEMBER,
    NO_GRANT,
    ALREADY_GRANTED,
    VALIDATION
}
