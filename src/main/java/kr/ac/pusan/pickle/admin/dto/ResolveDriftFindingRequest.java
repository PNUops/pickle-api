package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.Size;

/** Body of {@code POST /admin/drift-findings/{findingId}/resolve} (optional). */
public record ResolveDriftFindingRequest(
        @Size(max = 2000, message = "해결 메모는 2000자 이내로 입력해 주세요.") String note) {
}
