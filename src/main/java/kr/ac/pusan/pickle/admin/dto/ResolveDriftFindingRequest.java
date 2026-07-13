package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.Size;

/** Body of {@code POST /admin/drift-findings/{findingId}/resolve} (optional). */
public record ResolveDriftFindingRequest(
        @Size(max = 2000, message = "해결 메모는 2000자 이하여야 합니다.") String note) {
}
