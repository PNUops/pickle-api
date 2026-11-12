package kr.ac.pusan.pickle.llm.alert;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import kr.ac.pusan.pickle.config.LlmUsageAlertProperties;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.LlmApiKeyRepository;
import kr.ac.pusan.pickle.llm.LlmApiKeyStatus;
import kr.ac.pusan.pickle.notification.NotificationPreferencesService;
import kr.ac.pusan.pickle.notification.NotificationService;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded evaluations reuse retained quota events and the existing provider observation path. */
@Service
public class LlmUsageAlertService {
    private static final Duration STALE_AFTER = Duration.ofMinutes(30);
    private final JdbcTemplate jdbc;
    private final LlmApiKeyRepository keys;
    private final LlmUsageAlertState state;
    private final LlmUsageAlertAccess access;
    private final NotificationService notifications;
    private final NotificationPreferencesService preferences;
    private final LlmUsageAlertProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public LlmUsageAlertService(JdbcTemplate jdbc, LlmApiKeyRepository keys, LlmUsageAlertState state,
            LlmUsageAlertAccess access, NotificationService notifications,
            NotificationPreferencesService preferences, LlmUsageAlertProperties properties,
            Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.keys = keys; this.state = state; this.access = access;
        this.notifications = notifications; this.preferences = preferences; this.properties = properties;
        this.clock = clock; this.transactions = new TransactionTemplate(manager);
    }

    public int evaluate() {
        if (!properties.enabled()) { return 0; }
        int issued = 0; long cursor = 0;
        while (true) {
            List<Long> ids = jdbc.query("select id from llm_api_keys where id>? and status='ACTIVE' "
                    + "order by id limit 250", (rs, n) -> rs.getLong(1), cursor);
            if (ids.isEmpty()) { return issued; }
            for (Long id : ids) { issued += evaluateKey(id); }
            cursor = ids.getLast();
        }
    }

    public int evaluateKey(long id) {
        if (!properties.enabled()) { return 0; }
        return Objects.requireNonNull(transactions.execute(tx -> {
            LlmApiKey key = keys.findById(id).orElse(null);
            Instant now = clock.instant();
            if (key == null || key.effectiveStatus(now) != LlmApiKeyStatus.ACTIVE || !key.isIssued()) { return 0; }
            int count = 0;
            if (key.getDailyTokens() != null && key.getDailyTokens() > 0) {
                UsageAlertFacts facts = token(key, now);
                if (facts != null) { count += issue(key, facts, now); }
            }
            if (key.isCreditAxisConnected() && key.getOpenrouterKeyEnc() != null) {
                UsageAlertFacts facts = credit(key, now);
                if (facts != null) { count += issue(key, facts, now); }
            }
            return count;
        }));
    }

    private @Nullable UsageAlertFacts token(LlmApiKey key, Instant now) {
        var day = now.atZone(UsageAlertFacts.KST).toLocalDate();
        // This is the quota's request-time axis rule, not the ordinary trend's model join.
        var row = jdbc.queryForMap("""
                select coalesce(sum(e.input_tokens::bigint+e.output_tokens::bigint),0) as used,
                       max(e.received_at) as observed_at
                  from llm_usage_events e where e.key_id=?
                   and e.requested_at>=?::date::timestamp at time zone 'Asia/Seoul'
                   and e.requested_at<(?::date+1)::timestamp at time zone 'Asia/Seoul'
                   and (e.budget_axis='TOKEN' or (e.budget_axis is null and exists(
                       select 1 from llm_models m where m.public_name=e.public_model_name and m.budget_axis='TOKEN')))
                """, key.getId(), day, day);
        Timestamp at = (Timestamp) row.get("observed_at");
        if (at == null || !fresh(at.toInstant(), now)) { return null; }
        BigInteger used = new BigDecimal(row.get("used").toString()).toBigIntegerExact();
        return UsageAlertFacts.token(used, Objects.requireNonNull(key.getDailyTokens()), at.toInstant(), now);
    }

    private @Nullable UsageAlertFacts credit(LlmApiKey key, Instant now) {
        var value = state.credit(key.getId());
        if (value == null || !value.valid() || !value.limitReported() || !value.resetReported()
                || !Boolean.TRUE.equals(value.includeByok()) || !Boolean.FALSE.equals(value.disabled())
                || value.limit() == null || value.remaining() == null
                || !LlmUsageAlertState.authority(key).equals(value.authority())
                || !UsageAlertFacts.plain(key.getCreditLimit()).equals(value.configuredLimit())
                || !Objects.equals(LlmUsageAlertState.reset(key), value.configuredReset())
                || !Objects.equals(value.reset(), value.configuredReset())
                || !fresh(value.endedAt(), now) || value.endedAt().isBefore(value.startedAt())) { return null; }
        BigDecimal cap = new BigDecimal(value.limit());
        BigDecimal remaining = new BigDecimal(value.remaining());
        if (cap.compareTo(key.getCreditLimit()) != 0 || UsageAlertFacts.creditStage(cap, remaining) == null) { return null; }
        UsageAlertPeriod period = value.reset() == null ? UsageAlertPeriod.TOTAL
                : UsageAlertPeriod.valueOf(value.reset().toUpperCase(java.util.Locale.ROOT));
        Instant start = UsageAlertFacts.periodStart(period, now);
        Instant end = UsageAlertFacts.periodEnd(period, now);
        if (start != null && (value.startedAt().isBefore(start) || !value.endedAt().isBefore(Objects.requireNonNull(end)))) { return null; }
        if (!Objects.equals(UsageAlertFacts.periodStart(period, value.startedAt()),
                UsageAlertFacts.periodStart(period, value.endedAt()))) { return null; }
        var rows = jdbc.queryForList("select keys_error,keys_last_success_at from openrouter_accounts "
                + "where id=? and status='ACTIVE'", key.getOpenrouterAccountId());
        if (rows.isEmpty() || rows.getFirst().get("keys_error") != null
                || rows.getFirst().get("keys_last_success_at") == null) { return null; }
        if (!fresh(((Timestamp) rows.getFirst().get("keys_last_success_at")).toInstant(), now)) { return null; }
        return UsageAlertFacts.credit(cap, remaining, period, value.endedAt(), now);
    }

    private boolean fresh(Instant observed, Instant now) {
        return !observed.isAfter(now.plusSeconds(5)) && !observed.isBefore(now.minus(STALE_AFTER));
    }

    private int issue(LlmApiKey key, UsageAlertFacts facts, Instant now) {
        String basis = facts.basis(key.getPublicId());
        int previous = state.lockStage(key.getId(), facts.axis(), basis);
        if (facts.stage() <= previous) {
            if (facts.stage() == 0 && previous == 0) { state.advance(key.getId(), facts.axis(), basis, 0, 0); }
            return 0;
        }
        List<Long> candidates = access.owners(key.getId());
        int issued = 0;
        for (Long userId : access.eligibleReaders(key.getId(), candidates)) {
            boolean mail = facts.stage() == 100 && preferences.usageMailEnabled(userId);
            issued += notifications.publishUsage(userId, key, facts, now,
                    "llm_usage:" + basis + ":" + facts.stage(), mail);
        }
        state.advance(key.getId(), facts.axis(), basis, facts.stage(), issued);
        return issued;
    }
}
