package kr.ac.pusan.pickle.llm.openrouter;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cached vendor model catalogue and the one row that says how fresh it is.
 *
 * <p>The write is deliberately not a delete-and-reinsert. A model that stops
 * being listed keeps its row and loses its {@code listed} flag, because a key
 * whose fence names it still names it, and an approver reading that fence needs
 * the name to resolve to something rather than to a blank.
 */
@Repository
public class OpenRouterCatalogueRepository {

    private final JdbcTemplate jdbc;

    public OpenRouterCatalogueRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One catalogue row as the console reads it. */
    public record CatalogueRow(String modelId, String displayName, @Nullable String description,
            @Nullable Integer contextLength, @Nullable BigDecimal promptPrice,
            @Nullable BigDecimal completionPrice, boolean listed) {
    }

    /** The freshness facts, all of them nullable until a first fetch succeeds. */
    public record CatalogueState(@Nullable Instant lastAttemptAt, @Nullable Instant lastSuccessAt,
            @Nullable String lastError, int consecutiveFailures, @Nullable Integer lastModelCount) {
    }

    /**
     * Replaces the listing.
     *
     * <p>Everything happens in one transaction and in this order: seen models
     * are upserted, then anything not in this fetch is delisted. Delisting
     * first would leave a window where the catalogue is empty, and this table is
     * read by a screen.
     *
     * <p>The caller must not pass an empty list. A vendor answer that carried no
     * models is a failed fetch, not an empty catalogue, and is refused before it
     * reaches here — the whole point of keeping delisted rows is lost if one bad
     * response can switch every model off at once.
     */
    @Transactional
    public void replaceListing(List<OpenRouterClient.VendorModel> models, Instant now) {
        if (models.isEmpty()) {
            throw new IllegalArgumentException(
                    "refusing to apply an empty catalogue: an empty vendor answer is a failure, "
                            + "not a catalogue with no models");
        }
        Timestamp at = Timestamp.from(now);
        List<Object[]> batch = new ArrayList<>(models.size());
        for (OpenRouterClient.VendorModel model : models) {
            String id = model.id().toLowerCase(Locale.ROOT);
            batch.add(new Object[] {id, model.name(), model.description(), model.contextLength(),
                    model.promptPrice(), model.completionPrice(), at, at});
        }
        jdbc.batchUpdate("""
                insert into openrouter_catalogue_model (model_id, display_name, description,
                        context_length, prompt_price, completion_price, first_seen_at,
                        last_listed_at, listed, delisted_at)
                     values (?, ?, ?, ?, ?, ?, ?, ?, true, null)
                on conflict (model_id) do update
                    set display_name     = excluded.display_name,
                        description      = excluded.description,
                        context_length   = excluded.context_length,
                        prompt_price     = excluded.prompt_price,
                        completion_price = excluded.completion_price,
                        last_listed_at   = excluded.last_listed_at,
                        listed           = true,
                        delisted_at      = null
                """, batch);

        // Anything the fetch did not carry is switched off, not removed. The
        // timestamp comparison rather than an id list keeps this one statement
        // regardless of how many models the vendor lists.
        jdbc.update("""
                update openrouter_catalogue_model
                   set listed = false, delisted_at = coalesce(delisted_at, ?)
                 where listed and last_listed_at < ?
                """, at, at);

        jdbc.update("""
                insert into openrouter_catalogue_state (id, last_attempt_at, last_success_at,
                        last_error, consecutive_failures, last_model_count)
                     values (true, ?, ?, null, 0, ?)
                on conflict (id) do update
                    set last_attempt_at      = excluded.last_attempt_at,
                        last_success_at      = excluded.last_success_at,
                        last_error           = null,
                        consecutive_failures = 0,
                        last_model_count     = excluded.last_model_count
                """, at, at, models.size());
    }

    /**
     * Records a failed refresh without touching the listing.
     *
     * <p>{@code last_success_at} is left where it was on purpose: it is the
     * clock freshness is judged against, and moving it on a failure would make
     * a stale list look current.
     */
    public void recordFailure(String reason, Instant now) {
        Timestamp at = Timestamp.from(now);
        jdbc.update("""
                insert into openrouter_catalogue_state (id, last_attempt_at, last_error,
                        consecutive_failures)
                     values (true, ?, ?, 1)
                on conflict (id) do update
                    set last_attempt_at      = excluded.last_attempt_at,
                        last_error           = excluded.last_error,
                        consecutive_failures = openrouter_catalogue_state.consecutive_failures + 1
                """, at, reason);
    }

    public CatalogueState state() {
        List<CatalogueState> rows = jdbc.query("""
                select last_attempt_at, last_success_at, last_error, consecutive_failures,
                       last_model_count
                  from openrouter_catalogue_state
                 where id
                """, (rs, i) -> new CatalogueState(
                        rs.getTimestamp("last_attempt_at") == null
                                ? null : rs.getTimestamp("last_attempt_at").toInstant(),
                        rs.getTimestamp("last_success_at") == null
                                ? null : rs.getTimestamp("last_success_at").toInstant(),
                        rs.getString("last_error"),
                        rs.getInt("consecutive_failures"),
                        rs.getObject("last_model_count", Integer.class)));
        // No row yet means no refresh has ever run, which reads the same as one
        // that has never succeeded.
        return rows.isEmpty() ? new CatalogueState(null, null, null, 0, null) : rows.get(0);
    }

    /** Listed models, cheapest completion price first, unknown prices last. */
    public List<CatalogueRow> listed() {
        return jdbc.query("""
                select model_id, display_name, description, context_length, prompt_price,
                       completion_price, listed
                  from openrouter_catalogue_model
                 where listed
                 order by completion_price asc nulls last, model_id asc
                """, (rs, i) -> new CatalogueRow(rs.getString("model_id"),
                        rs.getString("display_name"), rs.getString("description"),
                        rs.getObject("context_length", Integer.class),
                        rs.getBigDecimal("prompt_price"), rs.getBigDecimal("completion_price"),
                        rs.getBoolean("listed")));
    }
}
