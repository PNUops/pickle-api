package kr.ac.pusan.pickle.common.web;

import jakarta.servlet.http.HttpServletRequest;

/** Client IP resolution for audit/rate-limit rows. */
public final class ClientIps {

    private ClientIps() {
    }

    /**
     * Resolves the caller IP behind the two-hop reverse-proxy chain
     * (Cloudflare → edge nginx on LXC100 → app-LXC nginx → pickle-api).
     *
     * <p>Because two trusted hops append to {@code X-Forwarded-For}, the
     * rightmost entry is the app-LXC nginx's own peer (LXC100 172.30.1.10) for
     * <em>every</em> external request — using it would collapse all traffic into
     * a single per-IP rate-limit bucket and erase audit attribution. The app-LXC
     * nginx sets {@code X-Real-IP} from Cloudflare's {@code CF-Connecting-IP}
     * (the true client), so it is preferred when present. Only when it is absent
     * (e.g. a direct in-cluster call) do we fall back to the rightmost
     * {@code X-Forwarded-For} entry, then {@code getRemoteAddr()}. Left
     * {@code X-Forwarded-For} entries stay untrusted (client-supplied, spoofable).</p>
     */
    public static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.strip();
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // limit -1 keeps trailing empty entries: "1.2.3.4," must fall back
            // to remoteAddr instead of resurrecting the spoofable left value.
            String[] hops = forwarded.split(",", -1);
            String rightmost = hops[hops.length - 1].strip();
            if (!rightmost.isEmpty()) {
                return rightmost;
            }
        }
        return request.getRemoteAddr();
    }
}
