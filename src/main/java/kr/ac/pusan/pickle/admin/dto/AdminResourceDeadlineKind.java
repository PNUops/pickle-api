package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** The meaning of a resource's current primary deadline, not a shared lifecycle state. */
@Schema(description = "기한 종류. VM 사용 종료, LLM 키 만료, 외부 도메인 갱신·예약, GPU 승인기간·실제 임대 종료를 구분합니다.")
public enum AdminResourceDeadlineKind {
    VM_USAGE_END, LLM_KEY_EXPIRY, DOMAIN_RENEWAL, DOMAIN_RESERVATION, GPU_GRANT_END, GPU_LEASE_END
}
