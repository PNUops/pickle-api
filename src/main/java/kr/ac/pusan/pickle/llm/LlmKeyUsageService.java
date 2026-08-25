package kr.ac.pusan.pickle.llm;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceStanding;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.llm.dto.LlmKeyBudgetResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyErrorTypeResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyHourlyUsageResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyLatencyResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyModelUsageResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyUsagePointResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyUsageTrendResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One key's usage over time (contract op {@code getLlmKeyUsage}).
 *
 * <p>Visibility is the key's own: a grant opens it, a member of the owning
 * workspace without one is refused in the open, a non-member is answered as if
 * the key did not exist. Usage is content, not standing — a workspace owner who
 * can see that the key exists still cannot read what it was used for, the same
 * line drawn at the key's detail.
 *
 * <p>Days are KST calendar days, the product's contractual timezone, and the
 * series is generated so that a day with no traffic is a zero row rather than a
 * missing one; a gap in a chart reads as "no data reached us", which is a
 * different and more alarming claim than "nobody called that day".
 *
 * <p>Rows are bucketed by {@code requested_at} — when the call happened — never
 * by arrival. Delivery is batched and at-least-once, so a call that straddles
 * midnight can reach the api after calls that happened later, and bucketing by
 * arrival would move it to the wrong day.
 *
 * <p>Events whose key never resolved carry a null {@code key_id} and so belong
 * to nobody's series. That is deliberate: they are the trace of somebody
 * looping on a bad key, and attributing them to a key would put another
 * person's failures in this key's chart.
 */
@Service
public class LlmKeyUsageService {

    /**
     * The status vocabulary is the gateway's, from the internal contract.
     * Anything outside it lands in {@code failed} rather than vanishing — a
     * status this query does not know about is still a request that happened.
     */
    private static final String TREND_SQL = """
            select d::date as day,
                   count(e.id) as requests,
                   count(*) filter (where e.status = 'OK') as succeeded,
                   count(*) filter (where e.status = 'RATE_LIMITED') as rate_limited,
                   count(*) filter (where e.status is not null
                                      and e.status not in ('OK', 'RATE_LIMITED')) as failed,
                   coalesce(sum(e.input_tokens), 0) as input_tokens,
                   coalesce(sum(e.output_tokens), 0) as output_tokens,
                   count(*) filter (where e.estimated) as estimated_requests
              from generate_series(?::date::timestamp, ?::date::timestamp, interval '1 day') d
              left join llm_usage_events e
                     on e.key_id = ?
                    and e.requested_at >= d::date::timestamp at time zone 'Asia/Seoul'
                    and e.requested_at < (d::date + 1)::timestamp at time zone 'Asia/Seoul'
             group by d
             order by d
            """;

    /**
     * The same window, broken down by model. Only models actually called
     * appear; a null name is a request that failed before a model was resolved
     * and is one bucket of its own rather than being dropped.
     */
    private static final String MODEL_SQL = """
            select e.public_model_name as model_name,
                   count(*) as requests,
                   count(*) filter (where e.status = 'OK') as succeeded,
                   count(*) filter (where e.status = 'RATE_LIMITED') as rate_limited,
                   count(*) filter (where e.status is null
                                      or e.status not in ('OK', 'RATE_LIMITED')) as failed,
                   coalesce(sum(e.input_tokens), 0) as input_tokens,
                   coalesce(sum(e.output_tokens), 0) as output_tokens,
                   count(*) filter (where e.estimated) as estimated_requests,
                   avg(e.latency_ms) as avg_latency_ms
              from llm_usage_events e
             where e.key_id = ?
               and e.requested_at >= ?::date::timestamp at time zone 'Asia/Seoul'
               and e.requested_at < (?::date + 1)::timestamp at time zone 'Asia/Seoul'
             group by e.public_model_name
             order by requests desc, model_name
            """;

    private static final String ERROR_TYPE_SQL = """
            select e.error_type, count(*) as requests
              from llm_usage_events e
             where e.key_id = ?
               and (e.status is null or e.status <> 'OK')
               and e.requested_at >= ?::date::timestamp at time zone 'Asia/Seoul'
               and e.requested_at < (?::date + 1)::timestamp at time zone 'Asia/Seoul'
             group by e.error_type
             order by requests desc, e.error_type
            """;

    /**
     * Percentiles over successful requests only. A timeout's duration is the
     * timeout setting and a refusal's is nearly zero, so including them would
     * move the numbers without saying anything about how the service performs.
     */
    private static final String LATENCY_SQL = """
            select percentile_cont(0.5) within group (order by e.latency_ms) as p50,
                   percentile_cont(0.9) within group (order by e.latency_ms) as p90,
                   percentile_cont(0.99) within group (order by e.latency_ms) as p99,
                   count(*) as samples
              from llm_usage_events e
             where e.key_id = ?
               and e.status = 'OK'
               and e.requested_at >= ?::date::timestamp at time zone 'Asia/Seoul'
               and e.requested_at < (?::date + 1)::timestamp at time zone 'Asia/Seoul'
            """;

    private static final String HOURLY_SQL = """
            select extract(isodow from e.requested_at at time zone 'Asia/Seoul')::int as weekday,
                   extract(hour from e.requested_at at time zone 'Asia/Seoul')::int as hour,
                   count(*) as requests
              from llm_usage_events e
             where e.key_id = ?
               and e.requested_at >= ?::date::timestamp at time zone 'Asia/Seoul'
               and e.requested_at < (?::date + 1)::timestamp at time zone 'Asia/Seoul'
             group by weekday, hour
             order by weekday, hour
            """;

    /**
     * Today's spend against the daily allowance, counted exactly the way the
     * quota sweep counts it — the same EXISTS on the model's axis, so the
     * number on the gauge and the number that refuses a request can never
     * disagree. Any other formulation would drift the day somebody re-axes a
     * model.
     */
    private static final String TODAY_TOKENS_SQL = """
            select coalesce(sum(e.input_tokens + e.output_tokens), 0)
              from llm_usage_events e
             where e.key_id = ?
               and e.requested_at >= ?::date::timestamp at time zone 'Asia/Seoul'
               and e.requested_at < (?::date + 1)::timestamp at time zone 'Asia/Seoul'
               and exists (select 1 from llm_models m
                            where m.public_name = e.public_model_name
                              and m.budget_axis = 'TOKEN')
            """;

    /**
     * The oldest and newest spend readings inside the forecast window. Two
     * points are the minimum a rate can be drawn from, and taking the oldest
     * inside a bounded window rather than the oldest ever keeps a burst last
     * week from flattening this week's slope.
     */
    private static final String SPEND_WINDOW_SQL = """
            select min(captured_at) as first_at, max(captured_at) as last_at,
                   min(usage_amount) as first_usage, max(usage_amount) as last_usage
              from llm_credit_usage_snapshots
             where key_id = ? and captured_at >= ?
            """;

    /** How far back the depletion forecast reads spend readings. */
    private static final Duration FORECAST_WINDOW = Duration.ofDays(7);

    /**
     * Below this the two readings are too close together to divide by: an hour
     * of unusual traffic would be projected as if it were the standing rate.
     */
    private static final Duration FORECAST_MINIMUM_SPAN = Duration.ofHours(48);

    /** Beyond this a forecast date is not information, so none is offered. */
    private static final int FORECAST_MAX_DAYS = 365;

    private final JdbcTemplate jdbcTemplate;
    private final LlmApiKeyRepository keyRepository;
    private final ResourceAccessResolver resourceAccessResolver;
    private final Clock clock;

    public LlmKeyUsageService(JdbcTemplate jdbcTemplate, LlmApiKeyRepository keyRepository,
            ResourceAccessResolver resourceAccessResolver, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.keyRepository = keyRepository;
        this.resourceAccessResolver = resourceAccessResolver;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public LlmKeyUsageTrendResponse trend(AuthenticatedUser actor, UUID keyId, int days) {
        LlmApiKey key = keyRepository.findByPublicId(keyId)
                .orElseThrow(() -> LlmKeyResourceAdapter.MESSAGES.notFound());
        ResourceStanding standing = resourceAccessResolver.standing(ResourceType.LLM_API_KEY,
                key.getId(), key.getWorkspaceId(), actor.id());
        standing.requireVisible(LlmKeyResourceAdapter.MESSAGES);

        LocalDate to = ClockConfig.todayKst(clock);
        LocalDate from = to.minusDays(days - 1L);
        List<LlmKeyUsagePointResponse> points = jdbcTemplate.query(TREND_SQL,
                (rs, rowNum) -> new LlmKeyUsagePointResponse(
                        rs.getObject("day", LocalDate.class),
                        rs.getLong("requests"),
                        rs.getLong("succeeded"),
                        rs.getLong("rate_limited"),
                        rs.getLong("failed"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("estimated_requests")),
                from, to, key.getId());
        return new LlmKeyUsageTrendResponse(from, to, reportedUntil(key.getId()), points,
                models(key.getId(), from, to), errorTypes(key.getId(), from, to),
                latency(key.getId(), from, to), hourly(key.getId(), from, to),
                budget(key, to));
    }

    private List<LlmKeyModelUsageResponse> models(long keyId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query(MODEL_SQL,
                (rs, rowNum) -> new LlmKeyModelUsageResponse(
                        rs.getString("model_name"),
                        rs.getLong("requests"),
                        rs.getLong("succeeded"),
                        rs.getLong("rate_limited"),
                        rs.getLong("failed"),
                        rs.getLong("input_tokens"),
                        rs.getLong("output_tokens"),
                        rs.getLong("estimated_requests"),
                        rs.getObject("avg_latency_ms") == null ? null
                                : Math.round(rs.getDouble("avg_latency_ms"))),
                keyId, from, to);
    }

    private List<LlmKeyErrorTypeResponse> errorTypes(long keyId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query(ERROR_TYPE_SQL,
                (rs, rowNum) -> new LlmKeyErrorTypeResponse(
                        rs.getString("error_type"), rs.getLong("requests")),
                keyId, from, to);
    }

    private @Nullable LlmKeyLatencyResponse latency(long keyId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query(LATENCY_SQL, rs -> {
            if (!rs.next() || rs.getLong("samples") == 0) {
                // No successful request in the window. Null rather than three
                // zeroes: a zero-millisecond p99 is a claim about speed.
                return null;
            }
            return new LlmKeyLatencyResponse(
                    Math.round(rs.getDouble("p50")),
                    Math.round(rs.getDouble("p90")),
                    Math.round(rs.getDouble("p99")),
                    rs.getLong("samples"));
        }, keyId, from, to);
    }

    private List<LlmKeyHourlyUsageResponse> hourly(long keyId, LocalDate from, LocalDate to) {
        return jdbcTemplate.query(HOURLY_SQL,
                (rs, rowNum) -> new LlmKeyHourlyUsageResponse(
                        rs.getInt("weekday"), rs.getInt("hour"), rs.getLong("requests")),
                keyId, from, to);
    }

    /**
     * Where the key stands against both budgets as of now. The token side is
     * counted here; the money side is read back from what OpenRouter last
     * reported, because they enforce that limit and our own arithmetic has no
     * prices to work from.
     */
    private LlmKeyBudgetResponse budget(LlmApiKey key, LocalDate today) {
        Long todayTokens = jdbcTemplate.queryForObject(TODAY_TOKENS_SQL, Long.class,
                key.getId(), today, today);
        return new LlmKeyBudgetResponse(
                key.getDailyTokens(),
                todayTokens == null ? 0L : todayTokens,
                key.isQuotaExhausted(),
                key.getCreditLimit(),
                key.getOpenrouterUsage(),
                key.getOpenrouterUsageAt(),
                creditDepletionForecast(key, today));
    }

    /**
     * When this key would reach its money limit at the rate it has been
     * spending. Null whenever the honest answer is "not enough to say" — too
     * few readings, too short a span, no spending, no limit, or a date so far
     * out that naming it would be false precision.
     */
    private @Nullable LocalDate creditDepletionForecast(LlmApiKey key, LocalDate today) {
        BigDecimal usage = key.getOpenrouterUsage();
        if (usage == null || key.getCreditLimit().signum() <= 0) {
            return null;
        }
        BigDecimal remaining = key.getCreditLimit().subtract(usage);
        if (remaining.signum() <= 0) {
            return today;
        }
        Instant since = clock.instant().minus(FORECAST_WINDOW);
        Forecast window = jdbcTemplate.query(SPEND_WINDOW_SQL, rs -> {
            if (!rs.next() || rs.getObject("first_at") == null) {
                return null;
            }
            return new Forecast(rs.getTimestamp("first_at").toInstant(),
                    rs.getTimestamp("last_at").toInstant(),
                    rs.getBigDecimal("first_usage"), rs.getBigDecimal("last_usage"));
        }, key.getId(), Timestamp.from(since));
        if (window == null) {
            return null;
        }
        Duration span = Duration.between(window.firstAt(), window.lastAt());
        BigDecimal spent = window.lastUsage().subtract(window.firstUsage());
        if (span.compareTo(FORECAST_MINIMUM_SPAN) < 0 || spent.signum() <= 0) {
            return null;
        }
        double perDay = spent.doubleValue() / (span.toMillis() / (double) Duration.ofDays(1)
                .toMillis());
        double daysLeft = remaining.doubleValue() / perDay;
        if (daysLeft > FORECAST_MAX_DAYS) {
            return null;
        }
        return today.plusDays((long) Math.ceil(daysLeft));
    }

    /** The two ends of the spend window a rate is drawn between. */
    private record Forecast(Instant firstAt, Instant lastAt, BigDecimal firstUsage,
            BigDecimal lastUsage) {
    }

    /**
     * The newest arrival for this key, which is how far the series can be
     * trusted — not the newest {@code requested_at}, because a batch still in
     * flight contains calls older than one already stored.
     */
    private Instant reportedUntil(long keyId) {
        return jdbcTemplate.queryForObject(
                "select max(received_at) from llm_usage_events where key_id = ?",
                Instant.class, keyId);
    }
}
