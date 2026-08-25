package kr.ac.pusan.pickle.oauth.dto;

import java.time.Instant;

/** Contract schema {@code OauthStartResponse} — where to send the browser. */
public record OauthStartResponse(String authorizationUrl, String state, Instant expiresAt) {
}
