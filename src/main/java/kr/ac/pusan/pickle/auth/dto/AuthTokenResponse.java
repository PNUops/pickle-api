package kr.ac.pusan.pickle.auth.dto;

/** Contract schema {@code AuthTokenResponse}. */
public record AuthTokenResponse(String accessToken, UserSummaryResponse user) {
}
