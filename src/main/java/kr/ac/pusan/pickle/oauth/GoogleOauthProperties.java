package kr.ac.pusan.pickle.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google sign-in configuration ({@code pickle.oauth.google.*}).
 *
 * <p>Missing credentials fail closed rather than fast: the OAuth endpoints
 * answer 503 and everything else runs. Development and test environments have
 * no Google client and must still start, which is the same call the Proxmox
 * block makes for the same reason.
 *
 * @param clientId       Google OAuth client id (empty disables the feature)
 * @param clientSecret   Google OAuth client secret
 * @param redirectUri    the CONSOLE page Google redirects to. It must match the
 *                       value registered in the Google console byte for byte.
 *                       The API issues no redirect of its own; the console
 *                       reads the code and posts it back same-origin.
 * @param hostedDomain   the Workspace domain accounts must belong to
 * @param authorizationUri Google's authorization endpoint
 * @param tokenUri       Google's token endpoint
 * @param jwkSetUri      Google's JWKS, for ID-token signature verification
 * @param issuers        accepted {@code iss} values (Google publishes two)
 */
@ConfigurationProperties(prefix = "pickle.oauth.google")
public record GoogleOauthProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String hostedDomain,
        String authorizationUri,
        String tokenUri,
        String jwkSetUri,
        java.util.List<String> issuers) {

    /** Whether the feature is configured at all; false makes the endpoints answer 503. */
    public boolean enabled() {
        return clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }
}
