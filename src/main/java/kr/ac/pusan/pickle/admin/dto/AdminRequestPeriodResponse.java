package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.UUID;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.request.period.RequestPeriodPreset;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code AdminRequestPeriod}: a usage period as the operator
 * manages it. Unlike the public {@code GET /request-periods} this carries the
 * status, the ordering and periods whose date has already passed, because
 * those are exactly what the operator has to see to keep the catalogue current.
 */
public record AdminRequestPeriodResponse(
        UUID id,
        String name,
        String displayName,
        @Schema(description = "종료일. 값이 없으면 무기한입니다.") @Nullable LocalDate endDate,
        CatalogStatus status,
        @Schema(description = "신청 화면에서의 표시 순서. 값이 같으면 먼저 만든 것이 앞에 옵니다.")
        int displayOrder,
        @Schema(description = "종료일이 이미 지나 신청 화면에 나오지 않는 항목인지.")
        boolean expired) {

    public static AdminRequestPeriodResponse from(RequestPeriodPreset preset, LocalDate today) {
        return new AdminRequestPeriodResponse(preset.getPublicId(), preset.getName(),
                preset.getDisplayName(), preset.getEndDate(), preset.getStatus(),
                preset.getDisplayOrder(),
                preset.getEndDate() != null && preset.getEndDate().isBefore(today));
    }
}
