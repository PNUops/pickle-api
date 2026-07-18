package kr.ac.pusan.pickle.meta;

import java.time.Duration;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.stereotype.Service;

/**
 * Cached read of the public system status (point 2/3 of the M6 maintenance
 * work): the {@code maintenance_mode}/{@code maintenance_message}/
 * {@code banner_message}/{@code contact_email} settings, refreshed at most once
 * per {@link #TTL}. Shared by {@link MetaController} (GET /meta/status) and the
 * maintenance filter so a settings toggle propagates within the TTL to both,
 * and so a hot request path (every non-exempt request consults the filter) does
 * not hit the DB per call.
 *
 * <p>The cached value is a volatile immutable snapshot; a stale read triggers a
 * single-flight refresh (double-checked under the monitor) while other threads
 * keep serving the previous snapshot — a settings change is never more than
 * {@link #TTL} stale, which the contract allows (≈15 s).</p>
 */
@Service
public class SystemStatusService {

    /** Max staleness of the cached snapshot (contract: changes apply within 15 s). */
    static final Duration TTL = Duration.ofSeconds(15);

    private final SettingsService settingsService;

    private volatile SystemStatusResponse cached;
    private volatile long refreshedAtNanos;

    public SystemStatusService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * Drops the cached snapshot so the next read re-queries — immediate
     * propagation for a settings change that must not wait out the TTL (used by
     * tests; callers otherwise rely on the ≤{@link #TTL} refresh).
     */
    public void invalidate() {
        cached = null;
    }

    /** The current status, from cache when fresh or a re-read when stale. */
    public SystemStatusResponse current() {
        SystemStatusResponse snapshot = cached;
        if (snapshot != null && System.nanoTime() - refreshedAtNanos < TTL.toNanos()) {
            return snapshot;
        }
        return refresh();
    }

    private synchronized SystemStatusResponse refresh() {
        // Another thread may have refreshed while we waited on the monitor.
        SystemStatusResponse snapshot = cached;
        if (snapshot != null && System.nanoTime() - refreshedAtNanos < TTL.toNanos()) {
            return snapshot;
        }
        SystemStatusResponse fresh = new SystemStatusResponse(
                settingsService.bool(SettingsService.MAINTENANCE_MODE, false),
                settingsService.string(SettingsService.MAINTENANCE_MESSAGE),
                settingsService.string(SettingsService.BANNER_MESSAGE),
                settingsService.string(SettingsService.CONTACT_EMAIL));
        cached = fresh;
        refreshedAtNanos = System.nanoTime();
        return fresh;
    }
}
