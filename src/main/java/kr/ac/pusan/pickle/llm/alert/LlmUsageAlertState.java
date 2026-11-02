package kr.ac.pusan.pickle.llm.alert;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.common.text.Secrets;
import kr.ac.pusan.pickle.config.LlmUsageAlertProperties;
import kr.ac.pusan.pickle.llm.LlmApiKey;
import kr.ac.pusan.pickle.llm.openrouter.OpenRouterKeyObservation;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** One current row per key/axis: latest typed facts and the last stable-basis stage only. */
@Component
public class LlmUsageAlertState {
    public record CreditSnapshot(boolean valid, boolean limitReported, @Nullable String limit,
            boolean resetReported, @Nullable String reset, @Nullable Boolean includeByok,
            @Nullable Boolean disabled, @Nullable String remaining, Instant startedAt, Instant endedAt,
            String configuredLimit, @Nullable String configuredReset, String authority) { }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final LlmUsageAlertProperties properties;

    public LlmUsageAlertState(JdbcTemplate jdbc, ObjectMapper mapper, LlmUsageAlertProperties properties) {
        this.jdbc = jdbc; this.mapper = mapper; this.properties = properties;
    }

    /** Called in the existing successful account observation transaction; it never calls a vendor. */
    public void capture(LlmApiKey key, @Nullable OpenRouterKeyObservation observation) {
        if (!properties.enabled() || observation == null || key.getOpenrouterAccountId() == null
                || key.getOpenrouterKeyHash() == null) { return; }
        CreditSnapshot snapshot = new CreditSnapshot(observation.valid(), observation.limitReported(),
                observation.limit() == null ? null : UsageAlertFacts.plain(observation.limit()),
                observation.resetReported(), observation.limitReset(), observation.includeByokInLimit(),
                observation.disabled(), observation.limitRemaining() == null ? null
                        : UsageAlertFacts.plain(observation.limitRemaining()),
                observation.requestStartedAt(), observation.requestEndedAt(),
                UsageAlertFacts.plain(key.getCreditLimit()), reset(key), authority(key));
        jdbc.update("""
                insert into llm_key_usage_alert_state(key_id,axis,credit_snapshot,updated_at)
                select k.id,'CREDIT',?::jsonb,now() from llm_api_keys k
                 where k.id=? and k.openrouter_account_id=? and k.openrouter_key_hash=?
                   and k.credit_limit=? and k.credit_limit_reset::text is not distinct from ?
                on conflict(key_id,axis) do update
                    set credit_snapshot=excluded.credit_snapshot,updated_at=now()
                """, mapper.writeValueAsString(snapshot), key.getId(), key.getOpenrouterAccountId(),
                key.getOpenrouterKeyHash(), key.getCreditLimit(),
                key.getCreditLimitReset() == null ? null : key.getCreditLimitReset().name());
    }

    public @Nullable CreditSnapshot credit(long keyId) {
        List<String> rows = jdbc.query("select credit_snapshot::text from llm_key_usage_alert_state "
                + "where key_id=? and axis='CREDIT' and credit_snapshot is not null", (rs, n) -> rs.getString(1), keyId);
        if (rows.isEmpty()) { return null; }
        try { return mapper.readValue(rows.getFirst(), CreditSnapshot.class); }
        catch (RuntimeException invalidStoredSnapshot) { return null; }
    }

    /** The caller's transaction keeps publication and advancement atomic. */
    public int lockStage(long keyId, UsageAlertAxis axis, String basis) {
        jdbc.update("insert into llm_key_usage_alert_state(key_id,axis,updated_at) values(?,?,now()) "
                + "on conflict(key_id,axis) do nothing", keyId, axis.name());
        return jdbc.queryForObject("select case when basis_key=? then highest_stage else 0 end "
                + "from llm_key_usage_alert_state where key_id=? and axis=? for update", Integer.class,
                basis, keyId, axis.name());
    }

    public void advance(long keyId, UsageAlertAxis axis, String basis, int stage, int recipients) {
        jdbc.update("update llm_key_usage_alert_state set basis_key=?,highest_stage=?,last_recipient_count=?,"
                + "updated_at=now() where key_id=? and axis=?", basis, stage, recipients, keyId, axis.name());
    }

    public static String authority(LlmApiKey key) {
        return Secrets.sha256Hex(key.getId() + ":" + key.getOpenrouterAccountId() + ":" + key.getOpenrouterKeyHash());
    }
    public static @Nullable String reset(LlmApiKey key) {
        return key.getCreditLimitReset() == null ? null : key.getCreditLimitReset().wireValue();
    }
}
