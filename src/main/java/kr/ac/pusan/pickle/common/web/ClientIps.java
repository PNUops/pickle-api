package kr.ac.pusan.pickle.common.web;

import jakarta.servlet.http.HttpServletRequest;

/** Client IP resolution for audit/rate-limit rows. */
public final class ClientIps {

    private ClientIps() {
    }

    /** Behind nginx: first X-Forwarded-For hop, else the socket address. */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].strip();
        }
        return request.getRemoteAddr();
    }
}
