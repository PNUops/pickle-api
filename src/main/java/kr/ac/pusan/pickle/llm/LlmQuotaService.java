package kr.ac.pusan.pickle.llm;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.config.ClockConfig;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Daily token quotas: <b>this service decides, the gateway refuses.</b>
 *
 * <p>Short-window limits — rpm, tpm, concurrency — belong to the gateway,
 * which observes the start and end of every request. A day's running total
 * does not: the gateway would need durable per-key state surviving restarts,
 * and it already ships every usage event here. So the api keeps the count and
 * publishes one boolean per key; the gateway's whole job is to answer
 * {@code quota_exhausted}.
 *
 * <p><b>The generation bump is the entire point of this class.</b> The gateway
 * is handed a document only when the generation moves, and only a write moves
 * it. A quota computed inside the sync query would be perfectly correct in the
 * database and would never once reach the gateway, because nothing would have
 * been written. So the flag is a column, and flipping it is a write that bumps
 * — which also means <b>bumping only when a row actually changed</b>: bumping
 * on every sweep would hand the gateway a full document every few minutes for
 * no reason, and the "unchanged" path exists precisely to avoid that.
 *
 * <p>Days are KST calendar days, the product's contractual timezone, and the
 * midnight reset needs no separate job: at 00:00 KST today's sum is zero, so
 * the next sweep clears every flag it set. That is also why the sweep must
 * keep running when nothing is being used — <b>the sweep is what unlocks a
 * key, not what locks it</b>, and a sweep that only ran on ingest would leave
 * an exhausted key locked until somebody tried to use it again.
 *
 * <p>Enforcement is as fresh as the last usage batch plus one poll, so a key
 * can overshoot its allowance by roughly one shipping window. That is inherent
 * to counting after the fact and is the trade the contract accepted: the
 * alternative is the gateway holding the count, which is the state it was
 * built not to have.
 */
@Service
public class LlmQuotaService {

    private static final Logger log = LoggerFactory.getLogger(LlmQuotaService.class);

    static final String JOB_ID = "llm-key-daily-quota";

    /**
     * Which limited keys are on the wrong side of their allowance right now.
     * The usage sum counts input and output alike: the granted figure is one
     * number, so splitting it here would invent a policy nobody approved.
     *
     * <p>Events with a null {@code key_id} never resolved to a key and belong
     * to nobody's allowance — charging them to a key would let one client's
     * bad-key loop exhaust somebody else's quota.
     *
     * <p>Only TOKEN-axis events count: the daily token allowance is
     * the self-serve budget, and commercial (CREDIT-axis) usage answers to the
     * key's money limit instead — summing both here would let paid traffic
     * drain the self-serve allowance. V99 events carry the request-time axis,
     * which wins even if the catalogue is later re-axed. Null is the additive
     * old-gateway shape; only those rows fall back to the current catalogue so
     * the API can deploy before the gateway without temporarily undercounting.
     * A null-axis passthrough name has no catalogue row and remains excluded.
     */
    private static final String DRIFTED_SQL = """
            select k.id,
                   coalesce(sum(e.input_tokens + e.output_tokens), 0) >= k.daily_tokens
                       as now_exhausted
              from llm_api_keys k
              left join llm_usage_events e
                     on e.key_id = k.id
                    and e.requested_at >= ?::date::timestamp at time zone 'Asia/Seoul'
                    and e.requested_at < (?::date + 1)::timestamp at time zone 'Asia/Seoul'
                    and (e.budget_axis = 'TOKEN'
                         or (e.budget_axis is null and exists (
                             select 1 from llm_models m
                              where m.public_name = e.public_model_name
                                and m.budget_axis = 'TOKEN')))
             where k.daily_tokens is not null
             group by k.id, k.quota_exhausted, k.daily_tokens
            having (coalesce(sum(e.input_tokens + e.output_tokens), 0) >= k.daily_tokens)
                   is distinct from k.quota_exhausted
             order by k.id
            """;

    private static final String APPLY_SQL =
            "update llm_api_keys set quota_exhausted = ? where id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final LlmGatewayGenerations generations;
    private final Clock clock;

    public LlmQuotaService(JdbcTemplate jdbcTemplate, LlmGatewayGenerations generations,
            Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.generations = generations;
        this.clock = clock;
    }

    /**
     * Runs often enough that a key is released early in the day it is released
     * on, and that an exhausted one stops within a few minutes of exhausting.
     * Also the only thing that clears a flag after midnight.
     */
    @Recurring(id = JOB_ID, cron = "*/5 * * * *", zoneId = "Asia/Seoul")
    public void sweep() {
        refresh();
    }

    /**
     * @return how many keys changed state — zero on the ordinary sweep, and
     *     zero means no generation was spent.
     */
    @Transactional
    public int refresh() {
        LocalDate today = ClockConfig.todayKst(clock);
        List<Map<String, Object>> drifted = jdbcTemplate.queryForList(DRIFTED_SQL, today, today);
        if (drifted.isEmpty()) {
            // The ordinary outcome. Bumping here would hand the gateway a full
            // document every five minutes forever, which is exactly what the
            // unchanged path exists to avoid.
            return 0;
        }
        // Bump BEFORE the writes it describes, as everywhere else in this tree:
        // the row lock the bump takes is held to commit, which is what makes
        // commit order and generation order agree. Reading first and bumping
        // only on a real change is not an exception to that rule — nothing has
        // been written yet.
        generations.bump();
        for (Map<String, Object> row : drifted) {
            jdbcTemplate.update(APPLY_SQL, row.get("now_exhausted"), row.get("id"));
        }
        log.info("daily quota state changed for {} key(s)", drifted.size());
        return drifted.size();
    }
}
