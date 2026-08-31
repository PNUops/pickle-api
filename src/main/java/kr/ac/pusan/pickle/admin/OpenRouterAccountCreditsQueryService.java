package kr.ac.pusan.pickle.admin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.admin.dto.OpenRouterAccountCreditsResponse;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterAccount;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCredentialError;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterCreditsFreshness;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterForecastUnavailableReason;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterUnmanagedSpendUnavailableReason;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes financial read models only from cached vendor observations. */
@Service
public class OpenRouterAccountCreditsQueryService {

    private static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private static final Duration FORECAST_WINDOW = Duration.ofDays(7);
    private static final Duration MIN_FORECAST_SPAN = Duration.ofHours(48);
    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public OpenRouterAccountCreditsQueryService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OpenRouterAccountCreditsResponse get(OpenRouterAccount account) {
        AccountState state = jdbcTemplate.queryForObject("""
                select credits_total, credits_usage, credits_observed_at,
                       credits_last_success_at, credits_last_attempt_at, credits_error,
                       paired_total_usage, paired_managed_usage,
                       paired_credits_observed_at, paired_keys_observed_at,
                       keys_last_success_at, keys_last_attempt_at, keys_error,
                       spend_baseline_total_usage, spend_baseline_observed_at,
                       spend_baseline_invalidated_at
                  from openrouter_accounts
                 where id = ?
                """, (rs, rowNum) -> new AccountState(
                        rs.getBigDecimal("credits_total"), rs.getBigDecimal("credits_usage"),
                        instant(rs.getTimestamp("credits_observed_at")),
                        instant(rs.getTimestamp("credits_last_success_at")),
                        instant(rs.getTimestamp("credits_last_attempt_at")),
                        rs.getString("credits_error") == null ? null
                                : OpenRouterCredentialError.valueOf(
                                        rs.getString("credits_error")),
                        rs.getBigDecimal("paired_total_usage"),
                        rs.getBigDecimal("paired_managed_usage"),
                        instant(rs.getTimestamp("paired_credits_observed_at")),
                        instant(rs.getTimestamp("paired_keys_observed_at")),
                        instant(rs.getTimestamp("keys_last_success_at")),
                        instant(rs.getTimestamp("keys_last_attempt_at")),
                        rs.getString("keys_error") == null ? null
                                : OpenRouterCredentialError.valueOf(rs.getString("keys_error")),
                        rs.getBigDecimal("spend_baseline_total_usage"),
                        instant(rs.getTimestamp("spend_baseline_observed_at")),
                        instant(rs.getTimestamp("spend_baseline_invalidated_at"))),
                account.getId());
        if (state == null) {
            throw new IllegalStateException("OpenRouter account disappeared while reading");
        }
        BigDecimal balance = state.totalCredits() == null || state.totalUsage() == null
                ? null : state.totalCredits().subtract(state.totalUsage());
        Forecast forecast = forecast(account.getId(), state, balance);
        BaselineUsage baseline = baselineUsage(state);
        return new OpenRouterAccountCreditsResponse(state.totalCredits(), state.totalUsage(),
                balance, freshness(state.lastSuccessAt()), state.observedAt(),
                state.lastSuccessAt(), state.lastAttemptAt(), state.error(),
                forecast.averageDailyUsage(), forecast.depletionAt(), forecast.reason(),
                forecast.windowStartedAt(), baseline.accountUsage(), baseline.managedUsage(),
                baseline.unmanagedUsage(), baseline.reason(), state.pairedCreditsObservedAt(),
                state.pairedKeysObservedAt(), freshness(state.keysLastSuccessAt()),
                state.keysLastSuccessAt(), state.keysLastAttemptAt(), state.keysError(),
                state.baselineObservedAt());
    }

    private Forecast forecast(long accountId, AccountState state,
            @Nullable BigDecimal balance) {
        if (state.observedAt() == null || state.totalUsage() == null || balance == null) {
            return Forecast.unavailable(OpenRouterForecastUnavailableReason.INSUFFICIENT_HISTORY);
        }
        Instant from = state.observedAt().minus(FORECAST_WINDOW);
        List<UsagePoint> points = jdbcTemplate.query("""
                select total_usage, credits_observed_at
                  from openrouter_credit_snapshots
                 where account_id = ? and credits_observed_at >= ?
                   and credits_observed_at <= ?
                 order by credits_observed_at, id
                """, (rs, rowNum) -> new UsagePoint(rs.getBigDecimal("total_usage"),
                        rs.getTimestamp("credits_observed_at").toInstant()),
                accountId, Timestamp.from(from), Timestamp.from(state.observedAt()));
        if (points.size() < 2) {
            return Forecast.unavailable(OpenRouterForecastUnavailableReason.INSUFFICIENT_HISTORY);
        }
        UsagePoint first = points.getFirst();
        UsagePoint last = points.getLast();
        Duration span = Duration.between(first.observedAt(), last.observedAt());
        if (span.compareTo(MIN_FORECAST_SPAN) < 0) {
            return Forecast.unavailable(OpenRouterForecastUnavailableReason.INSUFFICIENT_HISTORY);
        }
        BigDecimal previous = first.usage();
        for (int i = 1; i < points.size(); i++) {
            BigDecimal current = points.get(i).usage();
            if (current.compareTo(previous) < 0) {
                return Forecast.unavailable(OpenRouterForecastUnavailableReason.RESET_BOUNDARY);
            }
            previous = current;
        }
        BigDecimal delta = last.usage().subtract(first.usage());
        if (delta.signum() <= 0) {
            return Forecast.unavailable(OpenRouterForecastUnavailableReason.NO_CONSUMPTION);
        }
        BigDecimal averageDaily = delta.multiply(SECONDS_PER_DAY)
                .divide(BigDecimal.valueOf(span.getSeconds()), 6, RoundingMode.HALF_UP);
        if (balance.signum() <= 0) {
            return new Forecast(averageDaily, state.observedAt(), null, first.observedAt());
        }
        try {
            long seconds = balance.multiply(SECONDS_PER_DAY)
                    .divide(averageDaily, 0, RoundingMode.CEILING).longValueExact();
            return new Forecast(averageDaily, state.observedAt().plusSeconds(seconds),
                    null, first.observedAt());
        } catch (ArithmeticException | DateTimeException error) {
            return new Forecast(averageDaily, null,
                    OpenRouterForecastUnavailableReason.OUT_OF_RANGE, first.observedAt());
        }
    }

    private static BaselineUsage baselineUsage(AccountState state) {
        if (state.baselineTotalUsage() == null) {
            return new BaselineUsage(null, null, null,
                    OpenRouterUnmanagedSpendUnavailableReason.NO_BASELINE);
        }
        if (state.baselineInvalidatedAt() != null) {
            return new BaselineUsage(null, null, null,
                    OpenRouterUnmanagedSpendUnavailableReason.RESET_BOUNDARY);
        }
        if (state.pairedTotalUsage() == null || state.pairedManagedUsage() == null) {
            return new BaselineUsage(null, null, null,
                    OpenRouterUnmanagedSpendUnavailableReason.INCOMPLETE_PAIR);
        }
        BigDecimal accountUsage = state.pairedTotalUsage().subtract(state.baselineTotalUsage());
        if (accountUsage.signum() < 0) {
            return new BaselineUsage(null, null, null,
                    OpenRouterUnmanagedSpendUnavailableReason.RESET_BOUNDARY);
        }
        BigDecimal unmanaged = accountUsage.subtract(state.pairedManagedUsage());
        if (unmanaged.signum() < 0) {
            return new BaselineUsage(accountUsage, state.pairedManagedUsage(), null,
                    OpenRouterUnmanagedSpendUnavailableReason.INCOMPLETE_PAIR);
        }
        return new BaselineUsage(accountUsage, state.pairedManagedUsage(), unmanaged, null);
    }

    private OpenRouterCreditsFreshness freshness(@Nullable Instant lastSuccessAt) {
        if (lastSuccessAt == null) {
            return OpenRouterCreditsFreshness.UNKNOWN;
        }
        Duration age = Duration.between(lastSuccessAt, Instant.now(clock));
        return age.isNegative() || age.compareTo(STALE_AFTER) < 0
                ? OpenRouterCreditsFreshness.FRESH : OpenRouterCreditsFreshness.STALE;
    }

    private static @Nullable Instant instant(@Nullable Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record UsagePoint(BigDecimal usage, Instant observedAt) {
    }

    private record BaselineUsage(@Nullable BigDecimal accountUsage,
            @Nullable BigDecimal managedUsage, @Nullable BigDecimal unmanagedUsage,
            @Nullable OpenRouterUnmanagedSpendUnavailableReason reason) {
    }

    private record Forecast(@Nullable BigDecimal averageDailyUsage,
            @Nullable Instant depletionAt,
            @Nullable OpenRouterForecastUnavailableReason reason,
            @Nullable Instant windowStartedAt) {

        private static Forecast unavailable(OpenRouterForecastUnavailableReason reason) {
            return new Forecast(null, null, reason, null);
        }
    }

    private record AccountState(@Nullable BigDecimal totalCredits,
            @Nullable BigDecimal totalUsage, @Nullable Instant observedAt,
            @Nullable Instant lastSuccessAt, @Nullable Instant lastAttemptAt,
            @Nullable OpenRouterCredentialError error,
            @Nullable BigDecimal pairedTotalUsage,
            @Nullable BigDecimal pairedManagedUsage,
            @Nullable Instant pairedCreditsObservedAt,
            @Nullable Instant pairedKeysObservedAt,
            @Nullable Instant keysLastSuccessAt,
            @Nullable Instant keysLastAttemptAt,
            @Nullable OpenRouterCredentialError keysError,
            @Nullable BigDecimal baselineTotalUsage,
            @Nullable Instant baselineObservedAt,
            @Nullable Instant baselineInvalidatedAt) {
    }
}
