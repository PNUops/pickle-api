package kr.ac.pusan.pickle.sshgw.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * sshpiper → pickle-api route-resolution request (docs/api/internal.md Link 1).
 *
 * @param slug     the SSH username the client supplied ({@code vms.hostname})
 * @param sourceIp the real client IP sshpiper recovered from the PROXY protocol
 *                 v2 header — <b>reported</b> data, audited but never trusted for
 *                 authorization (which is done by the transport source check)
 */
public record RouteRequest(
        @NotBlank String slug,
        @NotBlank String sourceIp) {
}
