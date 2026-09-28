package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Bulk change kind {@code VM_PERIOD}: one new end date, or no end date, for every target. */
public record AdminBulkVmPeriodChange(
        @Schema(description = "새 종료일(KST, 이 날까지 포함). 무기한으로 바꾸려면 clearEndDate를 씁니다.")
        @Nullable LocalDate endDate,

        @Schema(description = "true면 종료일을 지워 무기한으로 만듭니다. endDate와 함께 보낼 수 없습니다.")
        @Nullable Boolean clearEndDate) {
}
