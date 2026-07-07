package kr.ac.pusan.pickle.common.web;

import jakarta.servlet.http.HttpServletRequest;

/** Client IP resolution for audit/rate-limit rows. */
public final class ClientIps {

    private ClientIps() {
    }

    /**
     * Resolves the caller IP behind exactly ONE trusted proxy (the nginx in
     * front of pickle-api on the same LXC — docs/plan/01/08).
     *
     * <p>The trusted proxy APPENDS the real peer address to
     * {@code X-Forwarded-For}, so only the RIGHTMOST entry can be trusted;
     * everything to its left is client-supplied and spoofable. Taking the
     * leftmost value would let an attacker rotate fake IPs to bypass per-IP
     * rate limits. If more proxy hops are ever added, this must count back
     * one entry per additional trusted hop.</p>
     */
    public static String clientIp(HttpServletRequest request) {
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
