package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Contract op {@code updateAdminLlmKeyExpiry} body: the new last day of use. */
public record AdminLlmKeyExpiryRequest(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                description = "새 종료일이에요(KST, 해당 날짜까지 포함). 키는 다음 날 0시에 만료돼요. "
                        + "오늘 이후로 지정해 주세요.")
        @NotNull(message = "종료일을 입력해 주세요.")
        LocalDate endDate) {
}
