package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** How one target of an applied bulk change ended (contract enum {@code AdminBulkChangeResult}). */
@Schema(description = "APPLIED: 변경됨. UNCHANGED: 이미 그 값이라 쓰지 않음. "
        + "SKIPPED: reason의 이유로 건너뜀. STALE: 미리보기 뒤 대상이 바뀌어 쓰지 않음.")
public enum AdminBulkChangeResult {
    APPLIED,
    UNCHANGED,
    SKIPPED,
    STALE
}
