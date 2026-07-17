package kr.ac.pusan.pickle.sshkey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Contract schema {@code SshKeyGenerateRequest} — server-generate a key. */
public record SshKeyGenerateRequest(
        @NotBlank @Size(min = 1, max = 100) String name) {
}
