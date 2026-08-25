package kr.ac.pusan.pickle.oauth.dto;

import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.oauth.OauthPurpose;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code OauthStartRequest}.
 *
 * <p>Carries no e-mail, deliberately. A {@code login_hint} would not change any
 * response, but it would make this endpoint something an anonymous caller can
 * put an address into, and then whether it is an oracle becomes an argument
 * rather than a fact. Not accepting one ends the argument.
 */
public record OauthStartRequest(
        /** Defaults to LOGIN. REVERIFY and LINK require a session. */
        @Nullable OauthPurpose purpose,
        /** Internal console path to return to; validated before it is echoed back. */
        @Size(max = 512) @Nullable String redirectTo) {
}
