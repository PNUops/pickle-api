package kr.ac.pusan.pickle.terminal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.stereotype.Component;

/**
 * In-memory <b>mirror</b> of live web-terminal sessions (docs/api/internal.md
 * Link 3, docs/plan/05 D7 I2). The real sessions (WS + SSH) are owned by the
 * bridge on LXC 102; pickle-api holds only a reported mirror, used for the admin
 * list and for concurrent-session cap accounting.
 *
 * <p>Lifecycle: {@link #registerPending} at redeem (the ticket was consumed but
 * the SSH channel may not be up yet), {@link #markStarted} at
 * {@code session-start} (SSH live), {@link #remove} at {@code session-end}. A
 * PENDING entry still counts toward caps (a minted-and-redeemed ticket is a
 * reserved slot); it just does not appear in the admin list until started.</p>
 *
 * <p>Single-instance: the mirror and the ticket store must live in exactly one
 * api process, or cap accounting and the admin view split. That is asserted at
 * boot by {@link TerminalSingleInstanceGuard} (PG advisory lock), not here — a
 * static JVM guard cannot see a second process and would false-trip across the
 * cached Spring test contexts of one JVM.</p>
 */
@Component
public class TerminalSessionRegistry {

    private final Clock clock;
    private final ConcurrentHashMap<String, MirrorSession> byId = new ConcurrentHashMap<>();

    public TerminalSessionRegistry(Clock clock) {
        this.clock = clock;
    }

    /**
     * One reported session. {@code startedAt}/{@code clientIp} are null while
     * PENDING (redeemed, SSH not yet reported live).
     */
    public record MirrorSession(String sessionId, long userId, UserRole userRole, long vmId,
            long orgId, String clientIp, Instant startedAt) {

        public boolean started() {
            return startedAt != null;
        }
    }

    /** Registers a redeemed-but-not-started session (counts toward caps). */
    public void registerPending(String sessionId, long userId, UserRole userRole, long vmId,
            long orgId) {
        byId.put(sessionId, new MirrorSession(sessionId, userId, userRole, vmId, orgId, null, null));
    }

    /**
     * Transitions PENDING → STARTED with the bridge-reported client IP. Returns
     * false when the session is unknown (never redeemed) or already started —
     * the caller answers 409 (inconsistent state) in that case.
     */
    public boolean markStarted(String sessionId, String clientIp) {
        boolean[] transitioned = {false};
        byId.computeIfPresent(sessionId, (id, existing) -> {
            if (existing.started()) {
                return existing; // already started — leave untouched, caller 409s
            }
            transitioned[0] = true;
            return new MirrorSession(existing.sessionId(), existing.userId(), existing.userRole(),
                    existing.vmId(), existing.orgId(), clientIp, clock.instant());
        });
        // false when the session is absent (never redeemed) or already started.
        return transitioned[0];
    }

    /** Removes the mirror entry (session-end); returns it if it was present. */
    public Optional<MirrorSession> remove(String sessionId) {
        return Optional.ofNullable(byId.remove(sessionId));
    }

    public Optional<MirrorSession> get(String sessionId) {
        return Optional.ofNullable(byId.get(sessionId));
    }

    /** All mirror entries (pending + started) — cap accounting sees every slot. */
    public List<MirrorSession> all() {
        return List.copyOf(byId.values());
    }

    /** Only started sessions, newest first — the admin list surface. */
    public List<MirrorSession> started() {
        return byId.values().stream()
                .filter(MirrorSession::started)
                .sorted((a, b) -> b.startedAt().compareTo(a.startedAt()))
                .toList();
    }

    public long countUser(long userId) {
        return byId.values().stream().filter(s -> s.userId() == userId).count();
    }

    public long countVm(long vmId) {
        return byId.values().stream().filter(s -> s.vmId() == vmId).count();
    }

    public long countOrg(long orgId) {
        return byId.values().stream().filter(s -> s.orgId() == orgId).count();
    }
}
