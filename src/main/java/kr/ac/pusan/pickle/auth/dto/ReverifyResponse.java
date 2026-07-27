package kr.ac.pusan.pickle.auth.dto;

import java.time.Instant;

/**
 * Contract schema {@code ReverifyResponse} (v0.24.0 sudo-mode): the raw
 * multi-use token the console keeps in memory and replays as
 * {@code X-Reauth-Token} on {@code @RequireReauth} endpoints until
 * {@code expiresAt}.
 */
public record ReverifyResponse(String reauthToken, Instant expiresAt) {
}
