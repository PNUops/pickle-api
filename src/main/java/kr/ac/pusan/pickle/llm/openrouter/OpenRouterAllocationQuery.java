package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditLimitReset;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
                   count(*) filter (where credit_limit > 0) as committed_key_count,
                   coalesce(sum(greatest(credit_limit
                       - coalesce(openrouter_usage, 0), 0)), 0) as remaining_commitment,
                   coalesce(sum(openrouter_accounted_usage), 0) as committed_usage,
                   count(*) filter (
                       where credit_limit > 0
                         and openrouter_key_hash is null) as awaiting_provision_key_count,
                   count(*) filter (
                       where credit_limit > 0
                         and openrouter_key_hash is not null
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
    private final ObjectMapper mapper;

    public OpenRouterAllocationQuery(JdbcTemplate jdbcTemplate, Clock clock, ObjectMapper mapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.mapper = mapper;
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
        }, ids.toString(), Timestamp.from(Instant.now(clock).truncatedTo(ChronoUnit.MICROS)));
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
                select credits_total, credits_usage, credits_observed_at, credits_error
                  from openrouter_accounts
                 where id = ?
                """, (rs, rowNum) -> {
            BigDecimal total = rs.getBigDecimal("credits_total");
            BigDecimal usage = rs.getBigDecimal("credits_usage");
            Timestamp observedAt = rs.getTimestamp("credits_observed_at");
            return new ObservedBalance(
                    total == null || usage == null ? null : total.subtract(usage),
                    observedAt == null ? null : observedAt.toInstant(),
                    rs.getString("credits_error"));
        }, accountId);
        return rows.isEmpty() ? new ObservedBalance(null, null, null) : rows.get(0);
    }

    /**
     * What a money-axis grant looked like against its account, for the record it
     * leaves behind. Over-allocating on purpose is allowed, since most holders
     * never spend their whole limit, so nothing here refuses anything; it exists
     * so that "why did this account run dry" has an answer later.
     *
     * <p>The comparison is <em>remaining</em> commitment against the balance,
     * not the committed total. The balance is already net of what these keys
     * have spent, so measuring the whole promise against it would charge the
     * same dollars twice and report an account as over-allocated while it can
     * still cover every outstanding claim.
     *
     * <p>{@code delta} is what this write adds to the commitment, and it is the
     * caller's arithmetic because only the caller knows what its own write does:
     * an approval adds a key that is not counted yet, while a limits replacement
     * swaps one figure for another on a key already counted. {@code overAllocated}
     * is null, not false, when the balance has never been observed: not knowing
     * is not the same as being within. The observation time and the last error
     * travel with it because a figure read three days ago and failing to refresh
     * since is not the same evidence as one read a minute ago.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> grantRecord(long accountId, BigDecimal delta) {
        Allocation allocation = forAccount(accountId);
        ObservedBalance balance = balance(accountId);
        BigDecimal projected = allocation.remainingCommitment().add(delta);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("accountCommittedCreditLimit", allocation.committedCreditLimit());
        record.put("accountRemainingCommitment", allocation.remainingCommitment());
        record.put("accountProjectedRemainingCommitment", projected);
        record.put("accountBalance", balance.amount());
        record.put("accountBalanceObservedAt", balance.observedAt());
        record.put("accountBalanceError", balance.error());
        record.put("overAllocated", balance.amount() == null ? null
                : projected.compareTo(balance.amount()) > 0);
        return record;
    }

    /** A replacement removes the old drawable part, not the old nominal cap. */
    @Transactional(readOnly = true)
    public Map<String, Object> replacementGrantRecord(long accountId, LlmApiKey key,
            BigDecimal nextLimit, @Nullable CreditLimitReset nextReset) {
        SnapshotBasis basis = snapshot(List.of(accountId), List.of(key.getId()));
        Allocation before = basis.allocations().get(accountId);
        SnapshotKey current = basis.keys().get(key.getId());
        if (current == null) { throw new IllegalStateException("key disappeared during commitment evaluation"); }
        ProjectedAllocation after = project(before, List.of(current.replacement(accountId, nextLimit, nextReset)));
        AccountObservation observation = basis.accounts().get(accountId);
        ObservedBalance balance = observation == null ? new ObservedBalance(null, null, null)
                : new ObservedBalance(observation.balance(), observation.creditsObservedAt(), observation.creditsError());
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("accountCommittedCreditLimit", before.committedCreditLimit());
        record.put("accountRemainingCommitment", before.remainingCommitment());
        record.put("accountProjectedRemainingCommitment", after.remainingCommitment());
        record.put("accountBalance", balance.amount());
        record.put("accountBalanceObservedAt", balance.observedAt());
        record.put("accountBalanceError", balance.error());
        record.put("overAllocated", balance.amount() == null || after.remainingCommitment() == null ? null
                : after.remainingCommitment().compareTo(balance.amount()) > 0);
        return record;
    }

    /**
     * All account sums, affected contributions and observation metadata come
     * from one SQL statement snapshot, including inside READ_COMMITTED callers.
     * Financial numeric JSON members are text to preserve decimal precision.
     */
    public SnapshotBasis snapshot(Collection<Long> accountIds, Collection<Long> keyIds) {
        Instant now = clock.instant();
        // For a stored microsecond lattice, strict expiry > actualNow is
        // exactly expiry > floorMicros(actualNow), without JDBC rounding up.
        Instant sqlNow = now.truncatedTo(ChronoUnit.MICROS);
        String sql = "with allocation as (" + ALLOCATION_SQL + """
                ), accounts as (
                    select id,public_id,name,credits_total::text,credits_usage::text,
                           credits_observed_at,credits_last_success_at,credits_last_attempt_at,credits_error,
                           keys_last_success_at,keys_last_attempt_at,keys_error
                      from openrouter_accounts where id=any(?::bigint[])
                ), targets as (
                    select id,openrouter_account_id,credit_limit::text,credit_limit_reset::text,
                           openrouter_usage::text,(openrouter_key_hash is not null) as provisioned,
                           status::text,expires_at,
                           (status in ('PENDING','ACTIVE','SUSPENDED') and (expires_at is null or expires_at>?)) as eligible
                      from llm_api_keys where id=any(?::bigint[])
                )
                select coalesce((select jsonb_agg(to_jsonb(x)) from (
                    select account_id,committed_credit_limit::text,committed_total_cap::text,
                           committed_daily::text,committed_weekly::text,committed_monthly::text,
                           committed_key_count,remaining_commitment::text,committed_usage::text,
                           awaiting_provision_key_count,usage_unreported_key_count from allocation
                ) x),'[]'::jsonb)::text as allocations,
                coalesce((select jsonb_agg(to_jsonb(accounts)) from accounts),'[]'::jsonb)::text as accounts,
                coalesce((select jsonb_agg(to_jsonb(targets)) from targets),'[]'::jsonb)::text as targets
                """;
        return jdbcTemplate.queryForObject(sql, (rs, n) -> {
            Map<Long, Allocation> allocations = new HashMap<>();
            accountIds.forEach(id -> allocations.put(id, Allocation.EMPTY));
            for (JsonNode row : mapper.readTree(rs.getString("allocations"))) {
                allocations.put(row.get("account_id").asLong(), new Allocation(decimal(row, "committed_credit_limit"),
                        decimal(row, "committed_total_cap"), decimal(row, "committed_daily"),
                        decimal(row, "committed_weekly"), decimal(row, "committed_monthly"),
                        row.get("committed_key_count").asLong(), decimal(row, "remaining_commitment"),
                        decimal(row, "committed_usage"), row.get("awaiting_provision_key_count").asLong(),
                        row.get("usage_unreported_key_count").asLong()));
            }
            Map<Long, AccountObservation> accounts = new HashMap<>();
            for (JsonNode row : mapper.readTree(rs.getString("accounts"))) {
                BigDecimal total = nullableDecimal(row, "credits_total");
                BigDecimal spent = nullableDecimal(row, "credits_usage");
                long id = row.get("id").asLong();
                accounts.put(id, new AccountObservation(id, UUID.fromString(row.get("public_id").asText()),
                        row.get("name").asText(), total == null || spent == null ? null : total.subtract(spent),
                        instant(row, "credits_observed_at"), instant(row, "credits_last_success_at"),
                        instant(row, "credits_last_attempt_at"), text(row, "credits_error"),
                        instant(row, "keys_last_success_at"), instant(row, "keys_last_attempt_at"),
                        text(row, "keys_error")));
            }
            Map<Long, SnapshotKey> keys = new HashMap<>();
            for (JsonNode row : mapper.readTree(rs.getString("targets"))) {
                long id = row.get("id").asLong();
                String reset = text(row, "credit_limit_reset");
                keys.put(id, new SnapshotKey(id, row.get("openrouter_account_id").isNull() ? null
                        : row.get("openrouter_account_id").asLong(), decimal(row, "credit_limit"),
                        reset == null ? null : CreditLimitReset.valueOf(reset), nullableDecimal(row, "openrouter_usage"),
                        row.get("provisioned").asBoolean(), LlmApiKeyStatus.valueOf(row.get("status").asText()),
                        instant(row, "expires_at"), row.get("eligible").asBoolean()));
            }
            return new SnapshotBasis(now, Map.copyOf(allocations), Map.copyOf(accounts), Map.copyOf(keys));
        }, ids(accountIds), Timestamp.from(sqlNow), ids(accountIds), Timestamp.from(sqlNow), ids(keyIds));
    }

    private static String ids(Collection<Long> ids) {
        return "{" + ids.stream().distinct().sorted().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(",")) + "}";
    }

    private static BigDecimal decimal(JsonNode row, String field) {
        return new BigDecimal(row.get(field).asText());
    }
    private static @Nullable BigDecimal nullableDecimal(JsonNode row, String field) {
        return row.get(field).isNull() ? null : decimal(row, field);
    }
    private static @Nullable String text(JsonNode row, String field) {
        return row.get(field).isNull() ? null : row.get(field).asText();
    }
    private static @Nullable Instant instant(JsonNode row, String field) {
        String value = text(row, field);
        return value == null ? null : OffsetDateTime.parse(value).toInstant();
    }

    public record SnapshotBasis(Instant observedAt, Map<Long, Allocation> allocations,
            Map<Long, AccountObservation> accounts, Map<Long, SnapshotKey> keys) { }
    public record AccountObservation(long id, UUID publicId, String name, @Nullable BigDecimal balance,
            @Nullable Instant creditsObservedAt, @Nullable Instant creditsLastSuccessAt,
            @Nullable Instant creditsLastAttemptAt, @Nullable String creditsError,
            @Nullable Instant keysLastSuccessAt, @Nullable Instant keysLastAttemptAt,
            @Nullable String keysError) { }
    public record SnapshotKey(long id, @Nullable Long accountId, BigDecimal creditLimit,
            @Nullable CreditLimitReset reset, @Nullable BigDecimal usage, boolean provisioned,
            LlmApiKeyStatus status, @Nullable Instant expiresAt, boolean eligible) {
        public Replacement replacement(long projectedAccount, BigDecimal nextLimit,
                @Nullable CreditLimitReset nextReset) {
            return new Replacement(id, eligible && Objects.equals(accountId, projectedAccount), eligible,
                    creditLimit, nextLimit, reset, nextReset, usage, provisioned);
        }
        public boolean matchesConfiguration(LlmApiKey key) {
            return Objects.equals(accountId, key.getOpenrouterAccountId())
                    && creditLimit.compareTo(key.getCreditLimit()) == 0 && reset == key.getCreditLimitReset()
                    && status == key.getStatus() && Objects.equals(expiresAt, key.getExpiresAt());
        }
    }

    /**
     * A whole proposed change evaluated against one server-cached allocation.
     * Missing meters retain their current conservative substitution, while a
     * changed vendor window cannot reuse the old window's measured amount.
     */
    public static ProjectedAllocation project(Allocation before, List<Replacement> replacements) {
        BigDecimal total = before.committedCreditLimit();
        BigDecimal cap = before.committedTotalCap();
        BigDecimal daily = before.committedDaily();
        BigDecimal weekly = before.committedWeekly();
        BigDecimal monthly = before.committedMonthly();
        BigDecimal remaining = before.remainingCommitment();
        long count = before.committedKeyCount();
        long awaiting = before.awaitingProvisionKeyCount();
        long unreported = before.usageUnreportedKeyCount();
        long changedPeriods = 0;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Replacement change : replacements) {
            if (!seen.add(change.keyId())) { throw new IllegalArgumentException("duplicate key replacement"); }
            BigDecimal old = change.countedBefore() ? change.oldLimit() : BigDecimal.ZERO;
            BigDecimal next = change.countedAfter() ? change.nextLimit() : BigDecimal.ZERO;
            total = total.subtract(old).add(next);
            cap = cap.subtract(windowAmount(old, change.oldReset(), null))
                    .add(windowAmount(next, change.nextReset(), null));
            daily = daily.subtract(windowAmount(old, change.oldReset(), CreditLimitReset.DAILY))
                    .add(windowAmount(next, change.nextReset(), CreditLimitReset.DAILY));
            weekly = weekly.subtract(windowAmount(old, change.oldReset(), CreditLimitReset.WEEKLY))
                    .add(windowAmount(next, change.nextReset(), CreditLimitReset.WEEKLY));
            monthly = monthly.subtract(windowAmount(old, change.oldReset(), CreditLimitReset.MONTHLY))
                    .add(windowAmount(next, change.nextReset(), CreditLimitReset.MONTHLY));
            boolean oldPositive = old.signum() > 0;
            boolean nextPositive = next.signum() > 0;
            count += (nextPositive ? 1 : 0) - (oldPositive ? 1 : 0);
            if (!change.provisioned()) { awaiting += (nextPositive ? 1 : 0) - (oldPositive ? 1 : 0); }
            if (change.provisioned() && change.observedUsage() == null) {
                unreported += (nextPositive ? 1 : 0) - (oldPositive ? 1 : 0);
            }
            remaining = remaining.subtract(drawable(old, change.observedUsage()))
                    .add(drawable(next, change.observedUsage()));
            if (change.countedBefore() && nextPositive && change.provisioned()
                    && change.oldReset() != change.nextReset()) { changedPeriods++; }
        }
        return new ProjectedAllocation(total, cap, daily, weekly, monthly, count,
                changedPeriods == 0 ? remaining : null, before.committedUsage(), awaiting, unreported,
                changedPeriods);
    }

    private static BigDecimal drawable(BigDecimal limit, @Nullable BigDecimal observedUsage) {
        return limit.subtract(observedUsage == null ? BigDecimal.ZERO : observedUsage).max(BigDecimal.ZERO);
    }

    private static BigDecimal windowAmount(BigDecimal amount, @Nullable CreditLimitReset actual,
            @Nullable CreditLimitReset requested) {
        return actual == requested ? amount : BigDecimal.ZERO;
    }

    public record Replacement(long keyId, boolean countedBefore, boolean countedAfter,
            BigDecimal oldLimit, BigDecimal nextLimit, @Nullable CreditLimitReset oldReset,
            @Nullable CreditLimitReset nextReset, @Nullable BigDecimal observedUsage, boolean provisioned) { }

    public record ProjectedAllocation(BigDecimal committedCreditLimit, BigDecimal committedTotalCap,
            BigDecimal committedDaily, BigDecimal committedWeekly, BigDecimal committedMonthly,
            long committedKeyCount, @Nullable BigDecimal remainingCommitment, BigDecimal committedUsage,
            long awaitingProvisionKeyCount, long usageUnreportedKeyCount, long changedPeriodKeys) { }

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

    /**
     * A cached balance, when it was read, and the classification of the last
     * failed attempt. Amount and time are null together when the vendor has
     * never answered; an error alongside a non-null amount means the figure is
     * real but the polling behind it is currently failing, which is what makes
     * an old {@code observedAt} worth recording next to a verdict.
     */
    public record ObservedBalance(@Nullable BigDecimal amount, @Nullable Instant observedAt,
            @Nullable String error) {
    }
}
