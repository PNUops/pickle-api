package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** Kind-specific stored dates beside the primary deadline; absent values remain absent. */
@Schema(description = "종류별 저장 날짜예요. 해당 종류에 없거나 저장되지 않은 날짜는 null이에요. 기한이 지났더라도 외부 처리 완료를 뜻하지 않아요.")
public record AdminResourceDeadlineDates(
        @Nullable LocalDate startDate,
        @Nullable LocalDate endDate,
        @Nullable Instant expiryStoppedAt,
        @Nullable Instant deleteScheduledFor,
        @Nullable Instant deleteRequestedAt,
        @Nullable Instant expiresAt,
        @Nullable Instant renewDueAt,
        @Nullable Instant releasedAt,
        @Nullable Instant reservedUntil,
        @Nullable LocalDate grantedStartDate,
        @Nullable LocalDate grantedEndDate,
        @Nullable Instant allocatedAt,
        @Nullable Instant leaseEndsAt) { }
