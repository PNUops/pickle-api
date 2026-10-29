package kr.ac.pusan.pickle.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Where one recipient of a many-person request stands (contract schema
 * {@code RequestRecipientStatus}).
 *
 * <p>{@code CREATING} is part of the published set but is not written today:
 * each recipient's resource is created in a single transaction, so the row
 * goes straight from {@code QUEUED} to {@code CREATED} or {@code FAILED}. A
 * VM's own provisioning is tracked by the VM, not by this row.</p>
 */
@Schema(description = "신청 대상자의 생성 상태. CREATED는 자원 행 등록이며 외부 준비 완료와 다릅니다. CANCELED는 자동 종료 또는 승인 결정을 유지한 개별 생성 대기 취소를 포함합니다.")
public enum RequestRecipientStatus {
    PENDING_JOIN,
    QUEUED,
    CREATING,
    CREATED,
    SKIPPED_EXPIRED,
    SKIPPED_INELIGIBLE,
    FAILED,
    CANCELED
}
