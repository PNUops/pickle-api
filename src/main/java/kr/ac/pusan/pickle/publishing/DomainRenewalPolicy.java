package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.stereotype.Component;

/**
 * How long an external name is held before its owner is asked again.
 *
 * <p>Its own class rather than a method on the sweep, because three places ask
 * it and only one of them is the sweep: a name gets its first deadline when it
 * is issued, a new one when its owner renews, and the sweep only reads what
 * those two wrote. A deadline computed differently in any of them would show
 * up as a name reclaimed early, which is the kind of thing nobody reports.</p>
 */
@Component
public class DomainRenewalPolicy {

    /** How long a renewal buys, and what a new name starts with. */
    public static final int DEFAULT_RENEWAL_DAYS = 180;

    /** Days before the deadline a notice goes out, when the setting is unset. */
    static final List<Integer> DEFAULT_NOTICE_DAYS = List.of(30, 7, 1);

    private final SettingsService settingsService;

    public DomainRenewalPolicy(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * The deadline as of {@code from}. Renewing is not adding to what is left
     * — the deadline moves to a full period from now, whenever the owner
     * presses it. The question it asks is whether anyone is still there, and
     * the answer does not get better for being given early.
     */
    public Instant deadlineFrom(Instant from) {
        return from.plus(renewalDays(), ChronoUnit.DAYS);
    }

    public int renewalDays() {
        return settingsService.integer(SettingsService.DOMAIN_RENEWAL_DAYS, DEFAULT_RENEWAL_DAYS);
    }

    /** Ascending, so the nearest stage covering a row is the first match. */
    List<Integer> noticeStages() {
        List<Integer> configured =
                settingsService.intList(SettingsService.DOMAIN_RENEWAL_NOTICE_DAYS);
        return (configured.isEmpty() ? DEFAULT_NOTICE_DAYS : configured).stream()
                .filter(stage -> stage >= 0)
                .sorted(Comparator.naturalOrder())
                .toList();
    }
}
