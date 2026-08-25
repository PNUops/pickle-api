package kr.ac.pusan.pickle.oauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Contract schema {@code OauthCallbackRequest}.
 *
 * <p>The console reads {@code code} and {@code state} off its own callback URL
 * and posts them here same-origin. The API never receives Google's redirect
 * itself: doing so would put the {@code Set-Cookie} for the session on a
 * response to a cross-site navigation, where a {@code SameSite=Strict} cookie
 * behaves differently across browsers, and would need the access token carried
 * back to the console in a URL.
 */
public record OauthCallbackRequest(
        @NotBlank @Size(max = 2048) String code,
        @NotBlank @Size(max = 128) String state) {
}
