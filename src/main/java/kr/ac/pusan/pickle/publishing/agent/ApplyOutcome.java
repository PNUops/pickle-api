package kr.ac.pusan.pickle.publishing.agent;

/**
 * Result of a proxy-agent {@code /apply} call (docs/api/internal.md Link 2):
 *
 * <ul>
 *   <li>{@code APPLIED} — 200, the vhost was rendered/removed and reloaded;</li>
 *   <li>{@code STALE} — 409, our generation ≤ the agent's applied one; the job
 *       treats this as "superseded, no-op";</li>
 *   <li>{@code FAILED} — 422, render/{@code nginx -t}/reload failure ({@code error}
 *       carries the nginx stderr) — a definitive agent answer, retrying the same
 *       config cannot succeed;</li>
 *   <li>{@code TRANSPORT} — no HTTP response at all (agent unreachable, timeout).
 *       Says nothing about the config: safe and worthwhile to retry.</li>
 * </ul>
 */
public record ApplyOutcome(Kind kind, Long generation, String error) {

    public enum Kind {
        APPLIED,
        STALE,
        FAILED,
        TRANSPORT
    }

    public static ApplyOutcome applied(Long generation) {
        return new ApplyOutcome(Kind.APPLIED, generation, null);
    }

    public static ApplyOutcome stale(Long generation) {
        return new ApplyOutcome(Kind.STALE, generation, null);
    }

    public static ApplyOutcome failed(String error) {
        return new ApplyOutcome(Kind.FAILED, null, error);
    }

    public static ApplyOutcome transport(String error) {
        return new ApplyOutcome(Kind.TRANSPORT, null, error);
    }
}
