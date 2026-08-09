package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code ApproveRequest} — the approve form. The granted period
 * and the reviewer's comment read the same for every resource type; what is
 * granted of the resource itself goes in the member named after its type.
 */
public record ApproveRequestRequest(
        @Nullable LocalDate grantedStartDate,

        @Nullable LocalDate grantedEndDate,

        @Size(max = 2000, message = "승인 의견은 2000자 이하여야 합니다.")
        @Nullable String comment,

        /** Required when approving a VM request, ignored otherwise. */
        @Valid @Nullable ApproveVmRequestSpec vm) {
}
