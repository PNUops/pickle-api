package kr.ac.pusan.pickle.llm;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceAccessResolver;
import kr.ac.pusan.pickle.access.ResourceStanding;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.llm.dto.LlmKeyUsagePointResponse;
import kr.ac.pusan.pickle.llm.dto.LlmKeyUsageTrendResponse;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
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
        return new LlmKeyUsageTrendResponse(from, to, reportedUntil(key.getId()), points);
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
