package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How much money an OpenRouter business account has already promised out.
 *
 * <p>The balance side of that question has been answered since v0.56.0; this is
 * the other half. An approver granting a money-axis limit needs to know what the
 * same account already owes before deciding whether to grant more, and
 * over-allocating deliberately is a legitimate call since most holders never
 * spend their whole limit. So this produces facts and never a verdict.
 *
 * <p>Live keys only, and live includes {@code PENDING}: a key that was approved
 * and not yet issued is money already committed. That is exactly the shape of
 * the incident this exists for, thirty approved course keys with none issued yet
 * and an account that reads as untouched.
 */
@Service
public class OpenRouterAllocationQuery {

    /**
     * One grouped pass over every account asked about. The account list already
     * runs several queries per account and this one is not allowed to join them.
     *
     * <p>Two usage columns appear here and they are not interchangeable.
     * {@code openrouter_usage} is what the vendor last reported against the
     * key's <em>current limit window</em> — it drops back at a reset, which is
     * how {@link OpenRouterSpendRecorder} detects one — so it is the only one
     * whose denominator matches {@code credit_limit}.
     * {@code openrouter_accounted_usage} is the reset-aware running total, so it
     * is the only one that answers "how much has this key spent". The vendor's
     * own {@code openrouter_limit_remaining} is in neither sum: it can still be
     * stated against a limit we have since changed, and mixing it in would leave
     * no way to tell which keys were which.
     */
    private static final String ALLOCATION_SQL = """
            select openrouter_account_id as account_id,
                   coalesce(sum(credit_limit), 0) as committed_credit_limit,
                   coalesce(sum(credit_limit)
                       filter (where credit_limit_reset is null), 0) as committed_total_cap,
                   coalesce(sum(credit_limit)
                       filter (where credit_limit_reset = 'DAILY'), 0) as committed_daily,
                   coalesce(sum(credit_limit)
                       filter (where credit_limit_reset = 'WEEKLY'), 0) as committed_weekly,
                   coalesce(sum(credit_limit)
                       filter (where credit_limit_reset = 'MONTHLY'), 0) as committed_monthly,
                   count(*) as committed_key_count,
                   coalesce(sum(greatest(credit_limit
                       - coalesce(openrouter_usage, 0), 0)), 0) as remaining_commitment,
                   coalesce(sum(openrouter_accounted_usage), 0) as committed_usage,
                   count(*) filter (
                       where openrouter_key_hash is null) as awaiting_provision_key_count,
                   count(*) filter (
                       where openrouter_key_hash is not null
                         and openrouter_usage is null) as usage_unreported_key_count
              from llm_api_keys
             where openrouter_account_id = any(?::bigint[])
               and status in ('PENDING'::llm_api_key_status,
                              'ACTIVE'::llm_api_key_status,
                              'SUSPENDED'::llm_api_key_status)
               and (expires_at is null or expires_at > ?)
             group by openrouter_account_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public OpenRouterAllocationQuery(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Allocation for every requested account, including the ones with no live
     * keys at all. An account nobody has drawn on has an allocation of zero, not
     * an unknown one, so no caller has to decide what a missing row meant.
     */
    @Transactional(readOnly = true)
    public Map<Long, Allocation> forAccounts(Collection<Long> accountIds) {
        Map<Long, Allocation> byAccount = new HashMap<>();
        for (Long accountId : accountIds) {
            byAccount.put(accountId, Allocation.EMPTY);
        }
        if (byAccount.isEmpty()) {
            return byAccount;
        }
        StringBuilder ids = new StringBuilder("{");
        for (Long accountId : byAccount.keySet()) {
            if (ids.length() > 1) {
                ids.append(',');
            }
            ids.append(accountId.longValue());
        }
        ids.append('}');
        jdbcTemplate.query(ALLOCATION_SQL, rs -> {
            byAccount.put(rs.getLong("account_id"), new Allocation(
                    rs.getBigDecimal("committed_credit_limit"),
                    rs.getBigDecimal("committed_total_cap"),
                    rs.getBigDecimal("committed_daily"),
                    rs.getBigDecimal("committed_weekly"),
                    rs.getBigDecimal("committed_monthly"),
                    rs.getLong("committed_key_count"),
                    rs.getBigDecimal("remaining_commitment"),
                    rs.getBigDecimal("committed_usage"),
                    rs.getLong("awaiting_provision_key_count"),
                    rs.getLong("usage_unreported_key_count")));
        }, ids.toString(), Timestamp.from(Instant.now(clock)));
        return byAccount;
    }

    /** The allocation of one account: the same query, asked about a single id. */
    @Transactional(readOnly = true)
    public Allocation forAccount(long accountId) {
        return forAccounts(List.of(accountId)).get(accountId);
    }

    /**
     * The cached balance of one account, for the record an approval leaves
     * behind. Reads the same DB cache the credits screen reads and never calls
     * the vendor; an account with no successful observation yet answers null
     * rather than zero.
     */
    @Transactional(readOnly = true)
    public ObservedBalance balance(long accountId) {
        List<ObservedBalance> rows = jdbcTemplate.query("""
                select credits_total, credits_usage, credits_observed_at
                  from openrouter_accounts
                 where id = ?
                """, (rs, rowNum) -> {
            BigDecimal total = rs.getBigDecimal("credits_total");
            BigDecimal usage = rs.getBigDecimal("credits_usage");
            Timestamp observedAt = rs.getTimestamp("credits_observed_at");
            return new ObservedBalance(
                    total == null || usage == null ? null : total.subtract(usage),
                    observedAt == null ? null : observedAt.toInstant());
        }, accountId);
        return rows.isEmpty() ? new ObservedBalance(null, null) : rows.get(0);
    }

    /**
     * What a money-axis grant looked like against its account, for the record it
     * leaves behind. Over-allocating on purpose is allowed — most holders never
     * spend their whole limit — so nothing here refuses anything; it exists so
     * that "why did this account run dry" has an answer later.
     *
     * <p>{@code delta} is what this write changes the commitment by, and it is
     * the caller's arithmetic because only the caller knows what its own write
     * does: an approval adds a key that is not counted yet, while a limits
     * replacement swaps one figure for another on a key already counted.
     * {@code overAllocated} is null, not false, when the balance has never been
     * observed: not knowing is not the same as being within.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> grantRecord(long accountId, BigDecimal delta) {
        Allocation allocation = forAccount(accountId);
        ObservedBalance balance = balance(accountId);
        BigDecimal projectedCommitment = allocation.committedCreditLimit().add(delta);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("accountCommittedCreditLimit", allocation.committedCreditLimit());
        record.put("accountProjectedCreditLimit", projectedCommitment);
        record.put("accountBalance", balance.amount());
        record.put("accountBalanceObservedAt", balance.observedAt());
        record.put("overAllocated", balance.amount() == null ? null
                : projectedCommitment.compareTo(balance.amount()) > 0);
        return record;
    }

    /**
     * The money an account has promised out, split by how each limit renews.
     *
     * <p>The four sums are separate because a total cap is a debt that goes out
     * once while a window limit refills every window, and a reader deciding
     * whether to top the account up needs to see which is which. They are added
     * together in {@link #committedCreditLimit()} all the same: the question an
     * over-allocation check asks is how much can leave this balance before the
     * next reset, and there the two are the same kind of number. Dropping the
     * window limits from that total would report an account carrying thirty
     * monthly keys as having allocated nothing.
     */
    public record Allocation(
            BigDecimal committedCreditLimit,
            BigDecimal committedTotalCap,
            BigDecimal committedDaily,
            BigDecimal committedWeekly,
            BigDecimal committedMonthly,
            long committedKeyCount,
            BigDecimal remainingCommitment,
            BigDecimal committedUsage,
            long awaitingProvisionKeyCount,
            long usageUnreportedKeyCount) {

        public static final Allocation EMPTY = new Allocation(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L, BigDecimal.ZERO,
                BigDecimal.ZERO, 0L, 0L);
    }

    /** A cached balance and when it was read, both null when never observed. */
    public record ObservedBalance(@Nullable BigDecimal amount, @Nullable Instant observedAt) {
    }
}
