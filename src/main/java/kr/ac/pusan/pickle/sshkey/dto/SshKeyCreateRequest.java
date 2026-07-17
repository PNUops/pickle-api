package kr.ac.pusan.pickle.sshkey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contract schema {@code SshKeyCreateRequest} — paste a public key. */
public record SshKeyCreateRequest(
        @NotBlank @Size(min = 1, max = 100) String name,
        @NotBlank String publicKey) {
}
