package kr.ac.pusan.pickle.sshkey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contract schema {@code SshKeyCreateRequest} — paste a public key. */
public record SshKeyCreateRequest(
        @NotBlank @Size(min = 1, max = 100) String name,
        // Bound the input before the parser base64-decodes it — any real key
        // (incl. RSA-16384 + comment) is well under this; it caps decode-time
        // allocation for an oversized paste (defence in depth on top of the
        // parser's 16 KB decoded-blob cap).
        @NotBlank @Size(max = 8192) String publicKey) {
}
