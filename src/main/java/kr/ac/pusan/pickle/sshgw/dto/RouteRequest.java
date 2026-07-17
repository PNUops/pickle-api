package kr.ac.pusan.pickle.sshgw.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * sshpiper → pickle-api route-resolution request (docs/api/internal.md Link 1,
 * v2). v2 adds the auth method and (for public-key auth) the offered key's
 * fingerprint, so the API can attribute the connection to a user.
 *
 * @param slug                 the SSH username the client supplied ({@code vms.hostname})
 * @param sourceIp             the real client IP sshpiper recovered from the PROXY
 *                             protocol v2 header — <b>reported</b> data, audited but
 *                             never trusted for authorization
 * @param authMethod           {@code "publickey"} or {@code "password"} — which
 *                             sshpiperd callback fired
 * @param publicKeyFingerprint OpenSSH SHA-256 fingerprint ({@code SHA256:<base64>});
 *                             required on the publickey path — only the fingerprint
 *                             travels, never the key blob
 * @param connectionId         sshpiperd's per-connection id (optional) — correlates
 *                             the several auth attempts of one connection in audit
 */
public record RouteRequest(
        @NotBlank String slug,
        @NotBlank String sourceIp,
        @NotBlank String authMethod,
        String publicKeyFingerprint,
        String connectionId) {

    public static final String AUTH_PUBLICKEY = "publickey";
    public static final String AUTH_PASSWORD = "password";
}
