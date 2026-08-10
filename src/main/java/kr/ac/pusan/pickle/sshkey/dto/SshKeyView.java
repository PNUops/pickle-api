package kr.ac.pusan.pickle.sshkey.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.common.crypto.SshKeyAlgorithm;
import kr.ac.pusan.pickle.sshkey.UserSshKey;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code SshKeyView} — a registered SSH public key. */
public record SshKeyView(
        UUID id,
        String name,
        SshKeyAlgorithm algorithm,
        String publicKey,
        String fingerprint,
        boolean privateKeyStored,
        Instant createdAt,
        @Nullable Instant lastUsedAt) {

    public static SshKeyView from(UserSshKey key) {
        return new SshKeyView(key.getPublicId(), key.getName(), key.algorithmEnum(), key.getPublicKey(),
                key.getFingerprintSha256(), key.isPrivateKeyStored(), key.getCreatedAt(),
                key.getLastUsedAt());
    }
}
