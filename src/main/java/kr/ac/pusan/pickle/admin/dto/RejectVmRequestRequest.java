package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Reject form (contract: the reason is mandatory and shown to the requester). */
public record RejectVmRequestRequest(
        @NotBlank(message = "반려 사유(comment)를 입력해 주세요.")
        @Size(max = 2000, message = "반려 사유는 2000자 이하여야 합니다.")
        String comment) {
}
