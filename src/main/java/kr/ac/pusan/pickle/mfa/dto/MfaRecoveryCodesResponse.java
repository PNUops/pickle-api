package kr.ac.pusan.pickle.mfa.dto;

import java.util.List;

/** Contract schema {@code MfaRecoveryCodesResponse} — the codes are shown only once. */
public record MfaRecoveryCodesResponse(List<String> recoveryCodes) {
}
