package kr.ac.pusan.pickle.sshkey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The ed25519 keypair the platform issued to one person for one VM (V85).
 *
 * <p>The key never reaches the guest: {@code authorized_keys} holds only the
 * platform keys, and the SSH gateway identifies the user by looking the offered
 * fingerprint up here before re-authenticating to the VM itself. That is why
 * scoping a key to a VM is a matter of this column rather than of provisioning,
 * and why {@code fingerprintSha256} must stay globally unique: it has to resolve
 * to exactly one owner.</p>
 *
 * <p>{@code privateKeyEnc} is the AES-GCM ciphertext of the private PEM, kept so
 * the owner can download it again. It never reaches a log or an audit detail.</p>
 */
@Entity
@Table(name = "vm_ssh_keys")
public class VmSshKey {

    /** Every issued key is ed25519; the column exists to record that, not to vary. */
    public static final String ALGORITHM = "ssh-ed25519";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The identifier this row wears outside the API boundary. Internal joins,
     * sorts and foreign keys keep using {@link #id}.
     */
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "public_id", nullable = false, updatable = false, unique = true)
    private UUID publicId = UUID.randomUUID();

    @Column(name = "vm_id", nullable = false)
    private Long vmId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String algorithm = ALGORITHM;

    @Column(name = "public_key", nullable = false)
    private String publicKey;

    @Column(name = "fingerprint_sha256", nullable = false)
    private String fingerprintSha256;

    @Column(name = "private_key_enc", nullable = false)
    private String privateKeyEnc;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    protected VmSshKey() {
    }

    public VmSshKey(Long vmId, Long userId, String publicKey, String fingerprintSha256,
            String privateKeyEnc) {
        this.vmId = vmId;
        this.userId = userId;
        this.publicKey = publicKey;
        this.fingerprintSha256 = fingerprintSha256;
        this.privateKeyEnc = privateKeyEnc;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getVmId() {
        return vmId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getAlgorithm() {
        return algorithm;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }
}
