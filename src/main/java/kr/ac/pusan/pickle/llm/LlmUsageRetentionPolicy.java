package kr.ac.pusan.pickle.llm;

import java.time.Clock;
import java.time.LocalDate;
import kr.ac.pusan.pickle.config.ClockConfig;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.stereotype.Component;

/**
 * How long raw usage events are kept, and therefore where the rollup stops
 * being rebuildable.
 *
 * <p>Two components need the same answer and must never disagree about it: the
 * sweep that deletes events, and the refresh that rebuilds day buckets from
 * them. A day whose events the sweep has taken can no longer be recomputed —
 * its rollup row is the surviving record — so the refresh has to know the same
 * cutoff the sweep used, or a late re-send would repaint a complete bucket
 * with whatever fragment survived.
 *
 * <p><b>Unlimited is the default.</b> Nothing is deleted until an operator sets
 * the retention explicitly, because the events are the only raw record of who
 * called what, and losing them is not undoable.
 */
@Component
public class LlmUsageRetentionPolicy {

    /**
     * The lower bound an operator may configure.
     *
     * <p>Not a comfort margin: the gateway spools every event on disk and
     * re-sends anything it could not confirm, so if the api forgets an event
     * sooner than the gateway forgets its copy, a lost checkpoint makes the
     * re-sent rows look new and they are counted a second time. The floor is
     * the gateway's own spool retention default; raising that one means
     * raising this one.
     */
    public static final int MINIMUM_RETENTION_DAYS = 90;

    /** No deletion at all — the default and the meaning of a zero setting. */
    public static final int UNLIMITED = 0;

    private final SettingsService settingsService;
    private final Clock clock;

    public LlmUsageRetentionPolicy(SettingsService settingsService, Clock clock) {
        this.settingsService = settingsService;
        this.clock = clock;
    }

    /** The configured retention in days, or {@link #UNLIMITED}. */
    public int retentionDays() {
        int configured = settingsService.integer(SettingsService.LLM_USAGE_RETENTION_DAYS, UNLIMITED);
        if (configured <= UNLIMITED) {
            return UNLIMITED;
        }
        // A value below the floor can only come from a hand-written row: the
        // settings validator refuses it. Read it as the floor rather than
        // obeying it, so no path can delete events the gateway may re-send.
        return Math.max(configured, MINIMUM_RETENTION_DAYS);
    }

    /**
     * The first KST day whose events are still kept, or {@code null} when
     * retention is unlimited. Days before it are swept and their rollup rows
     * are frozen.
     */
    public LocalDate sweptBefore() {
        int days = retentionDays();
        return days == UNLIMITED ? null : ClockConfig.todayKst(clock).minusDays(days);
    }
}
