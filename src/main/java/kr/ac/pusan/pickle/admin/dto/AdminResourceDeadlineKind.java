package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The meaning of a resource's current primary deadline, not a shared lifecycle state. */
@Schema(description = "VM 사용 종료와 LLM 키 만료, 외부 도메인 갱신과 예약, GPU 승인기간과 실제 임대 종료를 구분하는 기한 종류예요.")
public enum AdminResourceDeadlineKind {
    VM_USAGE_END, LLM_KEY_EXPIRY, DOMAIN_RENEWAL, DOMAIN_RESERVATION, GPU_GRANT_END, GPU_LEASE_END
}
