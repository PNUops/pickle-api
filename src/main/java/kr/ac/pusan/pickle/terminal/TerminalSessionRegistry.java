package kr.ac.pusan.pickle.terminal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import kr.ac.pusan.pickle.config.TerminalProperties;
import kr.ac.pusan.pickle.user.UserRole;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-memory <b>mirror</b> of live web-terminal sessions (the internal
 * web-terminal contract). The real sessions (WS + SSH) are owned by the
 * bridge on LXC 102; pickle-api holds only a reported mirror, used for the admin
 * list and for concurrent-session cap accounting.
 *
 * <p>Lifecycle: {@link #registerPending} at redeem (the ticket was consumed but
 * the SSH channel may not be up yet), {@link #markStarted} at
 * {@code session-start} (SSH live), {@link #touch} on every revalidation poll
 * (the 60s poll is the session heartbeat), {@link #remove} at {@code session-end}.
 * A PENDING entry still counts toward caps (a redeemed ticket is a reserved slot);
 * it just does not appear in the admin list until started.</p>
 *
 * <p><b>Leak prevention</b> ({@link #prune}): a bridge that dies between redeem
 * and session-start leaves a PENDING entry, and a hard-killed bridge leaves a
 * STARTED entry whose {@code session-end} never arrives — both would otherwise
 * pin a cap slot until the next api restart. {@code prune} (called lazily on the
 * cap-accounting and list paths) evicts a PENDING entry idle past
 * {@code pendingGrace} and a STARTED entry with no heartbeat past
 * {@code staleAfter}. Evicting a still-live session is fail-closed-consistent: the
 * bridge's next revalidation gets {@code SESSION_UNKNOWN} and closes it 1001.</p>
 *
 * <p>Single-instance: the mirror and the ticket store must live in exactly one
 * api process, or cap accounting and the admin view split. That is asserted at
 * boot by {@link TerminalSingleInstanceGuard} (PG advisory lock), not here — a
 * static JVM guard cannot see a second process and would false-trip across the
 * cached Spring test contexts of one JVM.</p>
 */
@Component
public class TerminalSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(TerminalSessionRegistry.class);

    private final Clock clock;
    private final Duration pendingGrace;
    private final Duration staleAfter;
    private final ConcurrentHashMap<String, MirrorSession> byId = new ConcurrentHashMap<>();

    public TerminalSessionRegistry(Clock clock, TerminalProperties properties) {
        this.clock = clock;
        this.pendingGrace = properties.pendingGrace();
        this.staleAfter = properties.staleAfter();
    }

    /**
     * One reported session. {@code startedAt}/{@code clientIp} are null while
     * PENDING (redeemed, SSH not yet reported live). {@code lastSeenAt} is the
     * newest sign of life (redeem, start, or a revalidation heartbeat) and drives
     * leak pruning.
     */
    public record MirrorSession(String sessionId, long userId, UserRole userRole, long vmId,
            long orgId, String clientIp, Instant startedAt, Instant lastSeenAt) {

        public boolean started() {
            return startedAt != null;
        }
    }

    /** Registers a redeemed-but-not-started session (counts toward caps). */
    public void registerPending(String sessionId, long userId, UserRole userRole, long vmId,
            long orgId) {
        Instant now = clock.instant();
        byId.put(sessionId,
                new MirrorSession(sessionId, userId, userRole, vmId, orgId, null, null, now));
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
            Instant now = clock.instant();
            return new MirrorSession(existing.sessionId(), existing.userId(), existing.userRole(),
                    existing.vmId(), existing.orgId(), clientIp, now, now);
        });
        // false when the session is absent (never redeemed) or already started.
        return transitioned[0];
    }

    /** Refreshes the heartbeat (revalidation poll) if the session is present. */
    public void touch(String sessionId) {
        byId.computeIfPresent(sessionId, (id, existing) -> new MirrorSession(existing.sessionId(),
                existing.userId(), existing.userRole(), existing.vmId(), existing.orgId(),
                existing.clientIp(), existing.startedAt(), clock.instant()));
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

    /**
     * Evicts leaked mirror entries (see class javadoc): PENDING idle past
     * {@code pendingGrace}, STARTED with no heartbeat past {@code staleAfter}. No
     * audit is written (an eviction is not a lifecycle event the user caused; a
     * genuinely-live session is caught by the bridge's next {@code SESSION_UNKNOWN}
     * revalidation). Called lazily on the cap and list paths.
     */
    public void prune() {
        Instant now = clock.instant();
        byId.values().removeIf(session -> {
            Duration idle = Duration.between(session.lastSeenAt(), now);
            boolean stale = session.started()
                    ? idle.compareTo(staleAfter) > 0
                    : idle.compareTo(pendingGrace) > 0;
            if (stale) {
                log.warn("pruning leaked terminal mirror session {} (started={}, idle={}s) — "
                        + "bridge never reported end; cap slot reclaimed", session.sessionId(),
                        session.started(), idle.toSeconds());
            }
            return stale;
        });
    }
}
