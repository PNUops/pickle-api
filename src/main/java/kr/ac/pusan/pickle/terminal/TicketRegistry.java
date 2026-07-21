package kr.ac.pusan.pickle.terminal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import kr.ac.pusan.pickle.config.TerminalProperties;
import kr.ac.pusan.pickle.user.UserRole;
import org.springframework.stereotype.Component;

/**
 * In-memory one-time ticket store for the web terminal (docs/api/internal.md
 * Link 3, docs/plan/05 Path B). Mint issues a 256-bit CSPRNG token carried to the
 * bridge as the {@code ticket.<t>} WS subprotocol element; redeem consumes it
 * exactly once.
 *
 * <p>Security properties:
 * <ul>
 *   <li><b>The raw ticket is never stored.</b> The map key is the SHA-256 hash of
 *       the ticket; a memory disclosure of this process reveals no usable
 *       tickets.</li>
 *   <li><b>Single-use is atomic.</b> Redeem is a {@link ConcurrentHashMap#remove}
 *       — two concurrent redeems of the same ticket race on the map, and only the
 *       one that wins the remove gets the entry; the other sees {@code null}. A
 *       redeemed ticket can never redeem again.</li>
 *   <li><b>TTL</b> (~60s from mint) is checked <em>after</em> the atomic consume,
 *       so an expired ticket is still consumed (cannot be retried) and rejected.</li>
 * </ul>
 *
 * <p>Expiry is reaped lazily (on mint and on the cap-count reads) — the TTL is
 * short enough that a stale entry lingers at most ~60s. The {@link Clock} is
 * injected so TTL expiry is deterministically testable.</p>
 */
@Component
public class TicketRegistry {

    /** 256-bit token → base64url is RFC 6455 subprotocol-token-safe (no padding). */
    private static final int TOKEN_BYTES = 32;

    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();
    private final Base64.Encoder base64Url = Base64.getUrlEncoder().withoutPadding();
    private final ConcurrentHashMap<String, Ticket> byHash = new ConcurrentHashMap<>();

    public TicketRegistry(Clock clock, TerminalProperties properties) {
        this.clock = clock;
        this.ttl = properties.ticketTtl();
    }

    /** The mint-time authorization decision bound to a ticket (redeem re-checks). */
    public record Ticket(String sessionId, long userId, long vmId, long orgId, UserRole userRole,
            Instant expiresAt) {
    }

    /** The value handed to the caller — the raw ticket travels only here, once. */
    public record Minted(String ticket, Instant expiresAt) {
    }

    /**
     * Issues a fresh one-time ticket bound to the session/user/VM. Returns the raw
     * ticket (to embed in the mint response) and its expiry.
     */
    public Minted mint(String sessionId, long userId, long vmId, long orgId, UserRole userRole) {
        purgeExpired();
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String ticket = base64Url.encodeToString(raw);
        Instant expiresAt = clock.instant().plus(ttl);
        byHash.put(hash(ticket), new Ticket(sessionId, userId, vmId, orgId, userRole, expiresAt));
        return new Minted(ticket, expiresAt);
    }

    /**
     * Atomically consumes the ticket and returns its bound decision, or empty when
     * the ticket is unknown, already used, or expired. The consume happens before
     * the TTL check, so an expired ticket is still removed (never retryable).
     */
    public Optional<Ticket> redeem(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        Ticket consumed = byHash.remove(hash(ticket));
        if (consumed == null) {
            return Optional.empty();
        }
        if (clock.instant().isAfter(consumed.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(consumed);
    }

    /** Live (unexpired, unredeemed) minted tickets for this user — cap accounting. */
    public long countUser(long userId) {
        purgeExpired();
        return byHash.values().stream().filter(t -> t.userId() == userId).count();
    }

    /** Live minted tickets for this VM — cap accounting. */
    public long countVm(long vmId) {
        purgeExpired();
        return byHash.values().stream().filter(t -> t.vmId() == vmId).count();
    }

    /** Live minted tickets for this owning org — cap accounting. */
    public long countOrg(long orgId) {
        purgeExpired();
        return byHash.values().stream().filter(t -> t.orgId() == orgId).count();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        byHash.values().removeIf(t -> now.isAfter(t.expiresAt()));
    }

    /** SHA-256 hex of the raw ticket — the only thing this process retains. */
    private static String hash(String ticket) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(ticket.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
