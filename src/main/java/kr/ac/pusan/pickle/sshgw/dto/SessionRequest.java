package kr.ac.pusan.pickle.sshgw.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

/**
 * sshpiperd → pickle-api authenticated-session audit request
 * (the internal SSH gateway route contract, {@code /internal/sshgw/session}). Sent
 * from {@code PipeStart} after signature verification.
 *
 * <p>Unlike the route lookup, this carries {@code candidateFingerprints} — the
 * full set of fingerprints the gateway got an <b>allowed</b> route decision for
 * on this connection, not a single winner. {@code PipeStart} does not expose
 * which key actually signed, so the API applies the distinct-owner rule: it
 * attributes the session only when every candidate resolves to one owner.</p>
 *
 * @param slug                  the VM hostname the client connected as
 * @param sourceIp              PROXY-recovered client IP (reported, audited, not trusted)
 * @param authMethod            {@code "publickey"} or {@code "password"}
 * @param candidateFingerprints publickey only: every route-allowed fingerprint of
 *                              this connection (the signer is one of them)
 * @param connectionId          sshpiperd's per-connection id (optional)
 */
public record SessionRequest(
        @NotBlank String slug,
        @NotBlank String sourceIp,
        @NotBlank String authMethod,
        List<String> candidateFingerprints,
        String connectionId) {
}
