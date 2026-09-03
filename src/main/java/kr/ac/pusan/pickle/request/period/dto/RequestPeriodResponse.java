package kr.ac.pusan.pickle.request.period.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import kr.ac.pusan.pickle.request.period.RequestPeriodPreset;

/** Contract schema {@code RequestPeriod}: a usage period the request form offers. */
public record RequestPeriodResponse(
        @Schema(description = "기간 항목 식별자") UUID id,

        @Schema(description = "화면에 보이는 이름", example = "2026학년도 1학기") String displayName,

        @Schema(description = "이 기간의 종료일", example = "2026-06-30")
        LocalDate endDate) {

    public static RequestPeriodResponse from(RequestPeriodPreset preset) {
        return new RequestPeriodResponse(preset.getPublicId(), preset.getDisplayName(),
                preset.getEndDate());
    }
}
