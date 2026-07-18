package kr.ac.pusan.pickle.mfa.dto;

/** Contract schema {@code MfaSetupResponse} — the secret is shown only here. */
public record MfaSetupResponse(String secret, String otpauthUri) {
}
