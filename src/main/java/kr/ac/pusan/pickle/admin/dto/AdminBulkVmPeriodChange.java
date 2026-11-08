package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Bulk change kind {@code VM_PERIOD}: one new end date, or no end date, for every target. */
public record AdminBulkVmPeriodChange(
        @Schema(description = "새 종료일이에요(KST, 해당 날짜까지 포함). 무기한으로 바꾸려면 clearEndDate를 사용해요.")
        @Nullable LocalDate endDate,

        @Schema(description = "true면 종료일을 지워 무기한으로 바꿔요. endDate와 함께 보낼 수 없어요.")
        @Nullable Boolean clearEndDate) {
}
