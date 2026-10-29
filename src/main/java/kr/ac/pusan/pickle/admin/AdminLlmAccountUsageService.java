package kr.ac.pusan.pickle.admin;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.admin.dto.AdminLlmAccountKeyUsageResponse;
import kr.ac.pusan.pickle.admin.dto.AdminLlmAccountUsageResponse;
import kr.ac.pusan.pickle.admin.dto.LlmConsumerSort;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.llm.dto.LlmUsageCostPointResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

/**
 * What one business account was used for (contract op
 * {@code getAdminLlmAccountUsage}).
 *
 * <p>Unlike the per-key surface beside it, this reads the daily rollup rather
 * than raw events. One account fans out to many keys and the window reaches
 * ninety days, so the raw scan would be the widest query on this screen; the
 * rollup already holds exactly these sums. The per-key surface reads raw
 * because it must agree with the holder's own screen, and no such pairing
 * exists here.
 *
 * <p>The join is deliberately from the rollup to the key and no further. Bucket
 * rows with a null key belong to no account and drop out of the join, which is
 * correct and is why the totals here are a subset rather than a spend figure.
 * The catalogue is not joined at all: paid models pass through without a row of
 * ours, so a catalogue join would silently drop the priced traffic.
 */
@Service
public class AdminLlmAccountUsageService {

    /**
     * Cost is summed only where a request was priced. The rollup's own column
     * is NOT NULL and reads zero for an unpriced bucket, so summing it blindly
     * would turn "we do not know" into "it was free"; the pair with
     * {@code priced_requests} is what carries the difference, and the caller
     * turns a zero-priced window into an absent amount rather than a zero one.
     */
    private static final String POINT_SQL = """
            select d::date as day,
                   coalesce(sum(x.cost_usd), 0) as cost_usd,
                   coalesce(sum(x.priced_requests), 0) as priced_requests,
                   coalesce(sum(x.requests), 0) as requests
              from generate_series(?::date::timestamp, ?::date::timestamp, interval '1 day') d
              left join (select u.day, u.cost_usd, u.priced_requests, u.requests
                           from llm_usage_daily u
                           join llm_api_keys k on k.id = u.key_id
                          where k.openrouter_account_id = ?) x
                     on x.day = d::date
             group by d
             order by d
            """;

    private static final String KEY_SQL = """
            select k.public_id as key_id,
                   k.name as key_name,
                   sum(u.requests) as requests,
                   sum(u.input_tokens) as input_tokens,
                   sum(u.output_tokens) as output_tokens,
                   sum(u.cost_usd) as cost_usd,
                   sum(u.priced_requests) as priced_requests
              from llm_usage_daily u
              join llm_api_keys k on k.id = u.key_id
             where k.openrouter_account_id = ?
               and u.day >= ?::date
               and u.day <= ?::date
             group by k.public_id, k.name
            having sum(u.requests) > 0
            """;

    private static final String LINKED_KEYS_SQL =
            "select count(*) from llm_api_keys where openrouter_account_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public AdminLlmAccountUsageService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * <b>Authorization is the caller's.</b> Nothing here reads the actor, so
     * the account must already have been scoped.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminLlmAccountUsageResponse usage(long accountId, int days) {
        return usage(accountId, days, 0, 20, LlmConsumerSort.REPORTED_COST_DESC);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AdminLlmAccountUsageResponse usage(long accountId, int days, int page, int size, LlmConsumerSort sort) {
        LocalDate to = ClockConfig.todayKst(clock);
        LocalDate from = to.minusDays(days - 1L);

        List<LlmUsageCostPointResponse> points = jdbcTemplate.query(POINT_SQL,
                (rs, rowNum) -> {
                    long priced = rs.getLong("priced_requests");
                    return new LlmUsageCostPointResponse(rs.getObject("day", LocalDate.class),
                            priced == 0 ? null : rs.getBigDecimal("cost_usd"),
                            priced, rs.getLong("requests"));
                },
                from, to, accountId);

        String order = switch (sort) {
            case REQUESTS_DESC -> "sum(u.requests) desc";
            case TOKENS_DESC -> "(sum(u.input_tokens)+sum(u.output_tokens)) desc";
            case REPORTED_COST_DESC -> "case when sum(u.priced_requests)>0 then sum(u.cost_usd) end desc nulls last";
        };
        List<AdminLlmAccountKeyUsageResponse> keys = jdbcTemplate.query(KEY_SQL
                        + " order by " + order + ",sum(u.requests) desc,k.name,k.public_id limit ? offset ?",
                (rs, rowNum) -> {
                    long priced = rs.getLong("priced_requests");
                    return new AdminLlmAccountKeyUsageResponse(
                            rs.getObject("key_id", UUID.class),
                            rs.getString("key_name"),
                            rs.getLong("requests"),
                            rs.getLong("input_tokens"),
                            rs.getLong("output_tokens"),
                            priced == 0 ? null : rs.getBigDecimal("cost_usd"),
                            priced);
                },
                accountId, from, to, size, (long) page * size);
        Totals totals = jdbcTemplate.queryForObject("select count(*) as keys_used,"
                        + "coalesce(sum(requests),0) as requests,coalesce(sum(priced_requests),0) as priced_requests,"
                        + "coalesce(sum(cost_usd),0) as cost_usd from (" + KEY_SQL + ") used_keys",
                (rs, n) -> new Totals(rs.getLong("keys_used"), rs.getLong("requests"),
                        rs.getLong("priced_requests"), rs.getBigDecimal("cost_usd")), accountId, from, to);
        if (totals == null) { throw new IllegalStateException("account usage totals missing"); }
        Long linked = jdbcTemplate.queryForObject(LINKED_KEYS_SQL, Long.class, accountId);
        return new AdminLlmAccountUsageResponse(from, to,
                totals.priced() == 0 ? null : totals.cost(), totals.requests(), totals.priced(),
                (int) Math.min(Integer.MAX_VALUE, totals.keys()), linked == null ? 0 : linked.intValue(),
                points, keys, clock.instant(), "Asia/Seoul", "DAILY_ROLLUP_ATTRIBUTION_CURRENT_ACCOUNT_BINDING",
                page, size, totals.keys(), (int) Math.min(Integer.MAX_VALUE, (totals.keys() + size - 1) / size), sort);
    }

    private record Totals(long keys, long requests, long priced, BigDecimal cost) { }
}
