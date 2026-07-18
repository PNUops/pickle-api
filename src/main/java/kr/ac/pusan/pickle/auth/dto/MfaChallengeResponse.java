package kr.ac.pusan.pickle.auth.dto;

/**
 * Contract schema {@code MfaChallengeResponse} — login stage-1 result for an
 * enrolled account. Distinguished from {@link AuthTokenResponse} by the
 * {@code mfaRequired} field (always true).
 */
public record MfaChallengeResponse(boolean mfaRequired, String mfaToken) {

    public static MfaChallengeResponse of(String mfaToken) {
        return new MfaChallengeResponse(true, mfaToken);
    }
}
