package kr.ac.pusan.pickle.llm.alert;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Threshold decisions use original integers/decimals; display values never decide a stage. */
public record UsageAlertFacts(UsageAlertAxis axis, UsageAlertPeriod period, String zone,
        @Nullable Instant start, @Nullable Instant end, Instant observedAt,
        @Nullable String usage, String limit, @Nullable String remaining, int stage) {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static @Nullable Integer tokenStage(@Nullable BigInteger used, @Nullable Long limit) {
        if (used == null || used.signum() < 0 || limit == null || limit <= 0) { return null; }
        BigInteger ceiling = BigInteger.valueOf(limit);
        if (used.compareTo(ceiling) >= 0) { return 100; }
        return used.multiply(BigInteger.valueOf(100)).compareTo(ceiling.multiply(BigInteger.valueOf(80))) >= 0 ? 80 : 0;
    }

    public static @Nullable Integer creditStage(@Nullable BigDecimal limit, @Nullable BigDecimal remaining) {
        if (limit == null || remaining == null || limit.signum() <= 0 || remaining.compareTo(limit) > 0) { return null; }
        if (remaining.signum() <= 0) { return 100; }
        return remaining.compareTo(limit.multiply(new BigDecimal("0.2"))) <= 0 ? 80 : 0;
    }

    public static UsageAlertFacts token(BigInteger used, long limit, Instant observed, Instant now) {
        ZonedDateTime day = now.atZone(KST).toLocalDate().atStartOfDay(KST);
        return new UsageAlertFacts(UsageAlertAxis.TOKEN, UsageAlertPeriod.DAILY, KST.getId(),
                day.toInstant(), day.plusDays(1).toInstant(), observed, used.toString(),
                Long.toString(limit), null, java.util.Objects.requireNonNull(tokenStage(used, limit)));
    }

    public static UsageAlertFacts credit(BigDecimal cap, BigDecimal remaining, UsageAlertPeriod period,
            Instant observed, Instant now) {
        Instant start = periodStart(period, now);
        return new UsageAlertFacts(UsageAlertAxis.CREDIT, period, "UTC", start,
                periodEnd(period, now), observed, null, plain(cap), plain(remaining),
                java.util.Objects.requireNonNull(creditStage(cap, remaining)));
    }

    public static @Nullable Instant periodStart(UsageAlertPeriod period, Instant now) {
        ZonedDateTime day = now.atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(ZoneOffset.UTC);
        return switch (period) {
            case TOTAL -> null;
            case DAILY -> day.toInstant();
            case WEEKLY -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toInstant();
            case MONTHLY -> day.withDayOfMonth(1).toInstant();
        };
    }

    public static @Nullable Instant periodEnd(UsageAlertPeriod period, Instant now) {
        Instant start = periodStart(period, now);
        if (start == null) { return null; }
        ZonedDateTime at = start.atZone(ZoneOffset.UTC);
        return switch (period) {
            case TOTAL -> null;
            case DAILY -> at.plusDays(1).toInstant();
            case WEEKLY -> at.plusWeeks(1).toInstant();
            case MONTHLY -> at.plusMonths(1).toInstant();
        };
    }

    /** Only window/cap/reset facts belong here, never an observation time or generation. */
    public String basis(UUID keyId) {
        return keyId + ":" + axis + ":" + period + ":" + (start == null ? "TOTAL" : start)
                + ":" + plain(new BigDecimal(limit));
    }

    public static String plain(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
}
