package kr.ac.pusan.pickle.sshkey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import kr.ac.pusan.pickle.common.crypto.SshKeyAlgorithm;
import org.hibernate.annotations.CreationTimestamp;

/**
 * A user's registered SSH public key (V28). Either pasted (server holds only the
 * public key) or server-generated ({@code privateKeyEnc} holds the AES-GCM
 * ciphertext of the private PEM for re-download). The {@code fingerprintSha256}
 * is globally unique and is the SSH gateway's identity lookup key.
 */
@Entity
@Table(name = "user_ssh_keys")
public class UserSshKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    /** OpenSSH key-type token ({@code ssh-ed25519}/{@code ssh-rsa}). */
    @Column(nullable = false)
    private String algorithm;

    @Column(name = "public_key", nullable = false)
    private String publicKey;

    @Column(name = "fingerprint_sha256", nullable = false)
    private String fingerprintSha256;

    /** AES-GCM ciphertext of the private PEM; null for pasted keys. */
    @Column(name = "private_key_enc")
    private String privateKeyEnc;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected UserSshKey() {
    }

    public UserSshKey(Long userId, String name, String algorithm, String publicKey,
            String fingerprintSha256, String privateKeyEnc) {
        this.userId = userId;
        this.name = name;
        this.algorithm = algorithm;
        this.publicKey = publicKey;
        this.fingerprintSha256 = fingerprintSha256;
        this.privateKeyEnc = privateKeyEnc;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    /** The console-facing algorithm enum, mapped from the stored wire token. */
    public SshKeyAlgorithm algorithmEnum() {
        return SshKeyAlgorithm.fromWireType(algorithm);
    }

    public String getPublicKey() {
        return publicKey;
    }

    public String getFingerprintSha256() {
        return fingerprintSha256;
    }

    public String getPrivateKeyEnc() {
        return privateKeyEnc;
    }

    public boolean isPrivateKeyStored() {
        return privateKeyEnc != null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
