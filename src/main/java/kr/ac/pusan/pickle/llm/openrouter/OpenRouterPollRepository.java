package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable due, trigger, claim and financial-state persistence for account polling. */
@Repository
public class OpenRouterPollRepository {

    private static final Duration CLAIM_LEASE = Duration.ofMinutes(10);
    private static final Duration TRIGGER_DEBOUNCE = Duration.ofMinutes(5);
    private static final Duration CREDIT_CADENCE = Duration.ofMinutes(10);
    private static final Duration KEY_CADENCE = Duration.ofMinutes(30);
    private static final Duration SNAPSHOT_RETENTION = Duration.ofDays(90);

    private final JdbcTemplate jdbcTemplate;

    public OpenRouterPollRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Long> dueAccountIds(Instant now) {
        return jdbcTemplate.queryForList("""
                select a.id
                  from openrouter_accounts a
                 where a.status = 'ACTIVE'::openrouter_account_status
                   and (a.poll_claim_token is null or a.poll_claim_until <= ?)
                   and exists (
                       select 1 from openrouter_account_credentials c
                        where c.account_id = a.id
                          and c.status = 'ACTIVE'::openrouter_credential_status
                          and c.verified_at is not null)
                   and (
                       ((a.full_refresh_requested_at is not null
                         or a.keys_next_due_at is null or a.keys_next_due_at <= ?)
                        and (a.keys_not_before_at is null or a.keys_not_before_at <= ?)
                        and (a.credits_not_before_at is null
                             or a.credits_not_before_at <= ?))
                       or ((a.credits_refresh_requested_at is not null
                            or a.credits_next_due_at is null
                            or a.credits_next_due_at <= ?)
                           and (a.credits_not_before_at is null
                                or a.credits_not_before_at <= ?)))
                 order by a.id
                 limit 100
                """, Long.class, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    /** Atomically chooses CREDITS or PAIR and binds the job to the current ACTIVE credential. */
    public @Nullable Claim claim(long accountId, Instant now) {
        UUID token = UUID.randomUUID();
        Timestamp at = Timestamp.from(now);
        Timestamp until = Timestamp.from(now.plus(CLAIM_LEASE));
        return jdbcTemplate.query("""
                with candidate as (
                    select a.id, a.public_id, c.id as credential_id,
                           a.full_refresh_requested_at,
                           a.credits_refresh_requested_at,
                           a.keys_next_due_at, a.credits_next_due_at,
                           a.keys_not_before_at, a.credits_not_before_at
                      from openrouter_accounts a
                      join openrouter_account_credentials c
                        on c.account_id = a.id
                       and c.status = 'ACTIVE'::openrouter_credential_status
                       and c.verified_at is not null
                     where a.id = ?
                       and a.status = 'ACTIVE'::openrouter_account_status
                       and (a.poll_claim_token is null or a.poll_claim_until <= ?)
                     order by c.id desc
                     limit 1
                ), chosen as (
                    select candidate.*,
                           case
                             when (full_refresh_requested_at is not null
                                   or keys_next_due_at is null or keys_next_due_at <= ?)
                               and (keys_not_before_at is null or keys_not_before_at <= ?)
                               and (credits_not_before_at is null
                                    or credits_not_before_at <= ?)
                               then 'PAIR'
                             when (credits_refresh_requested_at is not null
                                   or credits_next_due_at is null
                                   or credits_next_due_at <= ?)
                               and (credits_not_before_at is null
                                    or credits_not_before_at <= ?)
                               then 'CREDITS'
                             else null
                           end as kind
                      from candidate
                ), eligible as (
                    select * from chosen where kind is not null
                )
                update openrouter_accounts a
                   set poll_claim_token = ?, poll_claim_until = ?,
                       poll_claim_credential_id = eligible.credential_id,
                       poll_claim_kind = eligible.kind, poll_window_started_at = ?,
                       poll_claim_credits_request_at =
                           eligible.credits_refresh_requested_at,
                       poll_claim_full_request_at = case when eligible.kind = 'PAIR'
                           then eligible.full_refresh_requested_at else null end
                  from eligible
                 where a.id = eligible.id
                   and (a.poll_claim_token is null or a.poll_claim_until <= ?)
                   and exists (
                       select 1 from openrouter_account_credentials current_credential
                        where current_credential.id = eligible.credential_id
                          and current_credential.account_id = a.id
                          and current_credential.status =
                              'ACTIVE'::openrouter_credential_status)
                returning a.id, a.public_id, a.poll_claim_token,
                          a.poll_claim_credential_id, a.poll_claim_kind,
                          a.poll_window_started_at,
                          a.poll_claim_credits_request_at,
                          a.poll_claim_full_request_at
                """, rs -> rs.next() ? new Claim(rs.getLong(1),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getLong(4),
                        PollKind.valueOf(rs.getString(5)), rs.getTimestamp(6).toInstant(),
                        instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8))) : null,
                accountId, at, at, at, at, at, at, token, until, at, at);
    }

    public @Nullable Claim activeClaim(UUID accountId, UUID token, Instant now) {
        return jdbcTemplate.query("""
                select id, public_id, poll_claim_token, poll_claim_credential_id,
                       poll_claim_kind, poll_window_started_at,
                       poll_claim_credits_request_at, poll_claim_full_request_at
                  from openrouter_accounts
                 where public_id = ? and poll_claim_token = ? and poll_claim_until > ?
                """, rs -> rs.next() ? new Claim(rs.getLong(1),
                        rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getLong(4),
                        PollKind.valueOf(rs.getString(5)), rs.getTimestamp(6).toInstant(),
                        instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8))) : null,
                accountId, token, Timestamp.from(now));
    }

    public void markCreditsAttempt(Claim claim, Instant attemptedAt) {
        jdbcTemplate.update("""
                update openrouter_accounts
                   set credits_last_attempt_at = greatest(
                       coalesce(credits_last_attempt_at, ?), ?)
                 where id = ? and poll_claim_token = ?
                   and poll_claim_credential_id = ?
                   and exists (
                       select 1 from openrouter_account_credentials c
                        where c.id = ? and c.account_id = openrouter_accounts.id
                          and c.status = 'ACTIVE'::openrouter_credential_status)
                """, Timestamp.from(attemptedAt), Timestamp.from(attemptedAt),
                claim.accountId(), claim.token(), claim.credentialId(), claim.credentialId());
    }

    public void markKeysAttempt(Claim claim, Instant attemptedAt) {
        jdbcTemplate.update("""
                update openrouter_accounts
                   set keys_last_attempt_at = greatest(
                       coalesce(keys_last_attempt_at, ?), ?)
                 where id = ? and poll_claim_token = ?
                   and poll_claim_credential_id = ? and poll_claim_until > ?
                   and exists (
                       select 1 from openrouter_account_credentials c
                        where c.id = ? and c.account_id = openrouter_accounts.id
                          and c.status = 'ACTIVE'::openrouter_credential_status)
                """, Timestamp.from(attemptedAt), Timestamp.from(attemptedAt),
                claim.accountId(), claim.token(), claim.credentialId(),
                Timestamp.from(attemptedAt), claim.credentialId());
    }

    public void recordKeysSuccess(Claim claim, Instant observedAt) {
        jdbcTemplate.update("""
                update openrouter_accounts
                   set keys_last_success_at = greatest(
                           coalesce(keys_last_success_at, ?), ?),
                       keys_last_attempt_at = greatest(
                           coalesce(keys_last_attempt_at, ?), ?),
                       keys_error = null
                 where id = ? and poll_claim_token = ?
                   and poll_claim_credential_id = ?
                   and exists (
                       select 1 from openrouter_account_credentials c
                        where c.id = ? and c.account_id = openrouter_accounts.id
                          and c.status = 'ACTIVE'::openrouter_credential_status)
                """, Timestamp.from(observedAt), Timestamp.from(observedAt),
                Timestamp.from(observedAt), Timestamp.from(observedAt),
                claim.accountId(), claim.token(), claim.credentialId(), claim.credentialId());
    }

    public boolean baselineExists(Claim claim) {
        Boolean result = jdbcTemplate.queryForObject("""
                select spend_baseline_total_usage is not null
                  from openrouter_accounts
                 where id = ? and poll_claim_token = ?
                   and poll_claim_credential_id = ?
                   and exists (
                       select 1 from openrouter_account_credentials c
                        where c.id = ? and c.account_id = openrouter_accounts.id
                          and c.status = 'ACTIVE'::openrouter_credential_status)
                """, Boolean.class, claim.accountId(), claim.token(), claim.credentialId(),
                claim.credentialId());
        // The extra ACTIVE check above binds the baseline decision to the same
        // credential the claim captured.
        return Boolean.TRUE.equals(result);
    }

    /** Null means a provisioned managed key has never produced a complete usage reading. */
    public @Nullable BigDecimal managedUsageSinceBaseline(Claim claim) {
        return jdbcTemplate.queryForObject("""
                select case
                         when count(*) filter (
                             where openrouter_key_hash is not null
                               and openrouter_usage is null) > 0 then null
                         else coalesce(sum(openrouter_accounted_usage), 0)
                       end
                  from llm_api_keys
                 where openrouter_account_id = ?
                """, BigDecimal.class, claim.accountId());
    }

    @Transactional
    public boolean recordCreditsSuccess(Claim claim, OpenRouterClient.Credits credits,
            Instant creditsObservedAt, Instant completedAt) {
        if (claim.kind() != PollKind.CREDITS) {
            throw new IllegalArgumentException("credits-only completion requires CREDITS claim");
        }
        int changed = updateCreditsCurrent(claim, credits, creditsObservedAt,
                creditsObservedAt.plus(CREDIT_CADENCE), true);
        if (changed == 0) {
            return false;
        }
        insertSnapshot(claim, credits, null, null, creditsObservedAt, null, completedAt);
        consumeClaimedRequests(claim);
        clearClaim(claim);
        prune(completedAt);
        return true;
    }

    @Transactional
    public boolean recordPairSuccess(Claim claim, OpenRouterClient.Credits credits,
            BigDecimal managedUsage, Instant keysObservedAt, Instant creditsObservedAt,
            Instant completedAt) {
        return recordPairSuccess(claim, credits, managedUsage, false, keysObservedAt,
                creditsObservedAt, completedAt);
    }

    @Transactional
    public boolean recordPairSuccess(Claim claim, OpenRouterClient.Credits credits,
            BigDecimal managedUsage, boolean managedResetBoundary,
            Instant keysObservedAt, Instant creditsObservedAt, Instant completedAt) {
        if (claim.kind() != PollKind.PAIR) {
            throw new IllegalArgumentException("paired completion requires PAIR claim");
        }
        Baseline baseline = baseline(claim);
        if (baseline == null) {
            return false;
        }
        boolean firstBaseline = baseline.totalUsage() == null;
        BigDecimal baselineTotal = firstBaseline
                ? credits.totalUsage() : baseline.totalUsage();
        BigDecimal baselineManaged = firstBaseline ? managedUsage : baseline.managedUsage();
        BigDecimal accountDelta = credits.totalUsage().subtract(baselineTotal);
        BigDecimal managedDelta = managedUsage.subtract(baselineManaged);
        boolean baselineInvalid = managedResetBoundary || accountDelta.signum() < 0
                || managedDelta.signum() < 0;
        BigDecimal unmanaged = baselineInvalid ? null : accountDelta.subtract(managedDelta);
        if (unmanaged != null && unmanaged.signum() < 0) {
            baselineInvalid = true;
            unmanaged = null;
        }
        boolean resetBaseline = firstBaseline || baselineInvalid;
        BigDecimal nextBaselineTotal = resetBaseline
                ? credits.totalUsage() : baselineTotal;
        BigDecimal nextBaselineManaged = resetBaseline ? managedUsage : baselineManaged;
        Instant nextBaselineObservedAt = resetBaseline
                ? creditsObservedAt : baseline.observedAt();
        BigDecimal displayedManagedDelta = baselineInvalid ? BigDecimal.ZERO : managedDelta;
        UUID observationId = UUID.randomUUID();
        int changed = jdbcTemplate.update("""
                update openrouter_accounts
                   set credits_total = ?, credits_usage = ?, credits_observed_at = ?,
                       credits_last_success_at = ?, credits_last_attempt_at = ?,
                       credits_error = null, credits_failure_count = 0,
                       credits_not_before_at = null, credits_next_due_at = ?,
                       keys_failure_count = 0, keys_not_before_at = null,
                       keys_next_due_at = ?, keys_last_success_at = ?,
                       keys_last_attempt_at = ?, keys_error = null,
                       paired_window_id = ?,
                       paired_total_usage = ?, paired_managed_usage = ?,
                       paired_credits_observed_at = ?, paired_keys_observed_at = ?,
                       spend_baseline_total_usage = ?,
                       spend_baseline_managed_usage = ?,
                       spend_baseline_observed_at = ?,
                       spend_baseline_invalidated_at = ?
                 where id = ? and poll_claim_token = ?
                   and poll_claim_credential_id = ?
                   and exists (
                       select 1 from openrouter_account_credentials c
                        where c.id = ? and c.account_id = openrouter_accounts.id
                          and c.status = 'ACTIVE'::openrouter_credential_status)
                """, credits.totalCredits(), credits.totalUsage(),
                Timestamp.from(creditsObservedAt), Timestamp.from(creditsObservedAt),
                Timestamp.from(creditsObservedAt),
                Timestamp.from(creditsObservedAt.plus(CREDIT_CADENCE)),
                Timestamp.from(keysObservedAt.plus(KEY_CADENCE)),
                Timestamp.from(keysObservedAt), Timestamp.from(keysObservedAt), observationId,
                credits.totalUsage(), displayedManagedDelta, Timestamp.from(creditsObservedAt),
                Timestamp.from(keysObservedAt), nextBaselineTotal, nextBaselineManaged,
                Timestamp.from(nextBaselineObservedAt),
                baselineInvalid ? Timestamp.from(creditsObservedAt) : null,
                claim.accountId(), claim.token(),
                claim.credentialId(), claim.credentialId());
        if (changed == 0) {
            return false;
        }
        insertSnapshot(claim, credits, displayedManagedDelta, unmanaged, creditsObservedAt,
                keysObservedAt, completedAt, observationId);
        consumeClaimedRequests(claim);
        clearClaim(claim);
        prune(completedAt);
        return true;
    }

    public void recordFailure(Claim claim, FailureAxis axis, OpenRouterCredentialError error,
            Instant attemptedAt) {
        int failures = failureCount(claim, axis) + 1;
        Instant notBefore = attemptedAt.plus(backoff(claim.accountId(), failures, error));
        if (axis == FailureAxis.CREDITS) {
            jdbcTemplate.update("""
                    update openrouter_accounts
                       set credits_last_attempt_at = greatest(
                               coalesce(credits_last_attempt_at, ?), ?),
                           credits_error = cast(? as openrouter_credential_error),
                           credits_failure_count = ?,
                           credits_not_before_at = ?,
                           poll_claim_token = null, poll_claim_until = null,
                           poll_claim_credential_id = null, poll_claim_kind = null,
                           poll_window_started_at = null,
                           poll_claim_credits_request_at = null,
                           poll_claim_full_request_at = null
                     where id = ? and poll_claim_token = ?
                       and poll_claim_credential_id = ?
                       and exists (
                           select 1 from openrouter_account_credentials c
                            where c.id = ? and c.account_id = openrouter_accounts.id
                              and c.status = 'ACTIVE'::openrouter_credential_status)
                    """, Timestamp.from(attemptedAt), Timestamp.from(attemptedAt), error.name(),
                    failures, Timestamp.from(notBefore), claim.accountId(), claim.token(),
                    claim.credentialId(), claim.credentialId());
        } else {
            jdbcTemplate.update("""
                update openrouter_accounts
                       set keys_failure_count = ?, keys_not_before_at = ?,
                           keys_last_attempt_at = greatest(
                               coalesce(keys_last_attempt_at, ?), ?),
                           keys_error = cast(? as openrouter_credential_error),
                           poll_claim_token = null, poll_claim_until = null,
                           poll_claim_credential_id = null, poll_claim_kind = null,
                           poll_window_started_at = null,
                           poll_claim_credits_request_at = null,
                           poll_claim_full_request_at = null
                     where id = ? and poll_claim_token = ?
                       and poll_claim_credential_id = ?
                       and exists (
                           select 1 from openrouter_account_credentials c
                            where c.id = ? and c.account_id = openrouter_accounts.id
                              and c.status = 'ACTIVE'::openrouter_credential_status)
                    """, failures, Timestamp.from(notBefore), Timestamp.from(attemptedAt),
                    Timestamp.from(attemptedAt), error.name(), claim.accountId(), claim.token(),
                    claim.credentialId(), claim.credentialId());
        }
    }

    public void abandon(Claim claim) {
        clearClaim(claim);
    }

    public boolean requestRefresh(UUID accountId, boolean full, Instant now) {
        String column = full ? "full_refresh_requested_at" : "credits_refresh_requested_at";
        return jdbcTemplate.update("""
                update openrouter_accounts
                   set %s = ?
                 where public_id = ?
                   and greatest(coalesce(full_refresh_requested_at, '-infinity'),
                                coalesce(credits_refresh_requested_at, '-infinity'),
                                coalesce(last_triggered_refresh_at, '-infinity')) <= ?
                """.formatted(column), Timestamp.from(now), accountId,
                Timestamp.from(now.minus(TRIGGER_DEBOUNCE))) == 1;
    }

    /** A new ACTIVE credential invalidates an old credential's claim and backoff. */
    @Transactional
    public boolean requestAfterCredentialChange(UUID accountId, Instant now) {
        int changed = jdbcTemplate.update("""
                update openrouter_accounts
                   set full_refresh_requested_at = ?,
                       credits_not_before_at = null, keys_not_before_at = null,
                       credits_failure_count = 0, keys_failure_count = 0,
                       poll_claim_token = null, poll_claim_until = null,
                       poll_claim_credential_id = null, poll_claim_kind = null,
                       poll_window_started_at = null,
                       poll_claim_credits_request_at = null,
                       poll_claim_full_request_at = null
                 where public_id = ?
                """, Timestamp.from(now), accountId);
        return changed == 1;
    }

    private int updateCreditsCurrent(Claim claim, OpenRouterClient.Credits credits,
            Instant observedAt, Instant nextDueAt, boolean clearFailure) {
        return jdbcTemplate.update("""
                update openrouter_accounts
                   set credits_total = ?, credits_usage = ?, credits_observed_at = ?,
                       credits_last_success_at = ?, credits_last_attempt_at = ?,
                       credits_error = null,
                       credits_failure_count = case when ? then 0
                           else credits_failure_count end,
                       credits_not_before_at = case when ? then null
                           else credits_not_before_at end,
                       credits_next_due_at = ?
                 where id = ? and poll_claim_token = ?
                   and poll_claim_credential_id = ?
                   and exists (
                       select 1 from openrouter_account_credentials c
                        where c.id = ? and c.account_id = openrouter_accounts.id
                          and c.status = 'ACTIVE'::openrouter_credential_status)
                """, credits.totalCredits(), credits.totalUsage(), Timestamp.from(observedAt),
                Timestamp.from(observedAt), Timestamp.from(observedAt), clearFailure, clearFailure,
                Timestamp.from(nextDueAt), claim.accountId(), claim.token(), claim.credentialId(),
                claim.credentialId());
    }

    private @Nullable Baseline baseline(Claim claim) {
        return jdbcTemplate.query("""
                select spend_baseline_total_usage, spend_baseline_managed_usage,
                       spend_baseline_observed_at, spend_baseline_invalidated_at
                  from openrouter_accounts
                 where id = ? and poll_claim_token = ?
                   and poll_claim_credential_id = ?
                """, rs -> rs.next() ? new Baseline(rs.getBigDecimal(1), rs.getBigDecimal(2),
                        rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()) : null,
                claim.accountId(), claim.token(), claim.credentialId());
    }

    private int failureCount(Claim claim, FailureAxis axis) {
        String column = axis == FailureAxis.CREDITS
                ? "credits_failure_count" : "keys_failure_count";
        Integer value = jdbcTemplate.queryForObject("select " + column
                + " from openrouter_accounts where id = ? and poll_claim_token = ?",
                Integer.class, claim.accountId(), claim.token());
        return value == null ? 0 : Math.min(value, 30);
    }

    private void insertSnapshot(Claim claim, OpenRouterClient.Credits credits,
            @Nullable BigDecimal managedUsage, @Nullable BigDecimal unmanaged,
            Instant creditsObservedAt, @Nullable Instant keysObservedAt, Instant completedAt) {
        insertSnapshot(claim, credits, managedUsage, unmanaged, creditsObservedAt,
                keysObservedAt, completedAt, UUID.randomUUID());
    }

    private void insertSnapshot(Claim claim, OpenRouterClient.Credits credits,
            @Nullable BigDecimal managedUsage, @Nullable BigDecimal unmanaged,
            Instant creditsObservedAt, @Nullable Instant keysObservedAt, Instant completedAt,
            UUID observationId) {
        jdbcTemplate.update("""
                insert into openrouter_credit_snapshots
                       (account_id, observation_id, total_credits, total_usage,
                        managed_usage_since_baseline, unmanaged_usage,
                        window_started_at, credits_observed_at, keys_observed_at,
                        window_completed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, claim.accountId(), observationId, credits.totalCredits(),
                credits.totalUsage(), managedUsage, unmanaged,
                Timestamp.from(claim.windowStartedAt()), Timestamp.from(creditsObservedAt),
                keysObservedAt == null ? null : Timestamp.from(keysObservedAt),
                Timestamp.from(completedAt));
    }

    private void clearClaim(Claim claim) {
        jdbcTemplate.update("""
                update openrouter_accounts
                   set poll_claim_token = null, poll_claim_until = null,
                       poll_claim_credential_id = null, poll_claim_kind = null,
                       poll_window_started_at = null,
                       poll_claim_credits_request_at = null,
                       poll_claim_full_request_at = null
                 where id = ? and poll_claim_token = ?
                   and poll_claim_credential_id = ?
                """, claim.accountId(), claim.token(), claim.credentialId());
    }

    private void consumeClaimedRequests(Claim claim) {
        consumeRequest(claim, "credits_refresh_requested_at", claim.creditsRequestAt());
        if (claim.kind() == PollKind.PAIR) {
            consumeRequest(claim, "full_refresh_requested_at", claim.fullRequestAt());
        }
    }

    private void consumeRequest(Claim claim, String column, @Nullable Instant requestedAt) {
        if (requestedAt == null) {
            return;
        }
        Timestamp captured = Timestamp.from(requestedAt);
        jdbcTemplate.update("""
                update openrouter_accounts
                   set %1$s = case when %1$s <= ? then null else %1$s end,
                       last_triggered_refresh_at = greatest(
                           coalesce(last_triggered_refresh_at, ?), ?)
                 where id = ? and poll_claim_token = ?
                   and poll_claim_credential_id = ?
                """.formatted(column), captured, captured, captured,
                claim.accountId(), claim.token(), claim.credentialId());
    }

    private void prune(Instant completedAt) {
        jdbcTemplate.update("delete from openrouter_credit_snapshots where credits_observed_at < ?",
                Timestamp.from(completedAt.minus(SNAPSHOT_RETENTION)));
    }

    private static Duration backoff(long accountId, int failures,
            OpenRouterCredentialError error) {
        if (error != OpenRouterCredentialError.THROTTLED
                && error != OpenRouterCredentialError.VENDOR_UNAVAILABLE) {
            return Duration.ofMinutes(30);
        }
        int exponent = Math.min(failures - 1, 5);
        long minutes = Math.min(10L << exponent, 360L);
        long jitterSeconds = Math.floorMod(Long.hashCode(accountId * 31 + failures), 121);
        return Duration.ofMinutes(minutes).plusSeconds(jitterSeconds);
    }

    public enum PollKind { CREDITS, PAIR }
    public enum FailureAxis { CREDITS, KEYS }

    public record Claim(long accountId, UUID accountPublicId, UUID token, long credentialId,
            PollKind kind, Instant windowStartedAt, @Nullable Instant creditsRequestAt,
            @Nullable Instant fullRequestAt) {
    }

    private static @Nullable Instant instant(@Nullable Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private record Baseline(@Nullable BigDecimal totalUsage,
            @Nullable BigDecimal managedUsage, @Nullable Instant observedAt,
            @Nullable Instant invalidatedAt) {
    }
}
