package kr.ac.pusan.pickle.oauth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * Contract schema {@code OauthRegistrationResponse} — a verified Google
 * identity with no account yet. The console shows the onboarding form and
 * returns this token with the consents and the profile.
 */
public record OauthRegistrationResponse(
        @Schema(allowableValues = "REGISTRATION_REQUIRED") String kind,
        String registrationToken, String email,
        String name, Instant expiresAt) {

    public static OauthRegistrationResponse of(String registrationToken, String email, String name,
            Instant expiresAt) {
        return new OauthRegistrationResponse("REGISTRATION_REQUIRED", registrationToken, email, name,
                expiresAt);
    }
}
