package kr.ac.pusan.pickle.llm.openrouter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.llm.CreditModelPatterns;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/** One institution-owned OpenRouter business, funding and billing unit. */
@Entity
@Table(name = "openrouter_accounts")
public class OpenRouterAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "public_id", nullable = false, updatable = false)
    private UUID publicId = UUID.randomUUID();

    @Column(name = "org_id", nullable = false, updatable = false)
    private Long orgId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private OpenRouterAccountStatus status = OpenRouterAccountStatus.ACTIVE;

    @Column(name = "program")
    private @Nullable String program;

    @Column(name = "contact")
    private @Nullable String contact;

    @Column(name = "vendor_workspace_id")
    private @Nullable UUID vendorWorkspaceId;

    @Column(name = "vendor_identity_key_hash")
    private @Nullable String vendorIdentityKeyHash;

    /**
     * The money-axis model allow list an approval form prefills from, as a JSON
     * array. It is a copy source, never an inheritance root: an approval reads
     * it once and stores the result on the key, so editing it here moves no
     * already-issued key and writes nothing the gateway polls.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_credit_allowed_models", nullable = false, columnDefinition = "jsonb")
    private String defaultCreditAllowedModels = CreditModelPatterns.EMPTY_JSON;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected OpenRouterAccount() {
    }

    public OpenRouterAccount(long orgId, String name, @Nullable String program,
            @Nullable String contact, long createdBy) {
        this.orgId = orgId;
        this.name = name;
        this.program = program;
        this.contact = contact;
        this.createdBy = createdBy;
    }

    public void update(String name, @Nullable String program,
            @Nullable String contact, OpenRouterAccountStatus status, Instant now) {
        this.name = name;
        this.program = program;
        this.contact = contact;
        this.status = status;
        this.updatedAt = now;
    }

    /**
     * Replaces the prefill default.
     *
     * <p>Separate from {@link #update} because the caller decides whether the
     * request carried the member at all, and because this write must not be
     * mistaken for one that reaches the gateway document — it does not, and
     * {@code AdminOpenRouterAccountService} says why it takes no generation
     * bump.
     */
    public void replaceDefaultCreditAllowedModels(String models, Instant now) {
        this.defaultCreditAllowedModels = models;
        this.updatedAt = now;
    }

    /** The stored JSON array; read it with {@link CreditModelPatterns#fromJson}. */
    public String getDefaultCreditAllowedModels() {
        return defaultCreditAllowedModels;
    }

    public void discoverVendorWorkspace(UUID vendorWorkspaceId, Instant now) {
        if (this.vendorWorkspaceId != null && !this.vendorWorkspaceId.equals(vendorWorkspaceId)) {
            throw new IllegalStateException("OpenRouter vendor workspace is immutable");
        }
        this.vendorWorkspaceId = vendorWorkspaceId;
        this.updatedAt = now;
    }

    public void establishVendorIdentityKey(String hash, Instant now) {
        if (vendorIdentityKeyHash != null && !vendorIdentityKeyHash.equals(hash)) {
            throw new IllegalStateException("OpenRouter vendor billing identity is immutable");
        }
        vendorIdentityKeyHash = hash;
        updatedAt = now;
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getOrgId() { return orgId; }
    public String getName() { return name; }
    public OpenRouterAccountStatus getStatus() { return status; }
    public @Nullable String getProgram() { return program; }
    public @Nullable String getContact() { return contact; }
    public @Nullable UUID getVendorWorkspaceId() { return vendorWorkspaceId; }
    public @Nullable String getVendorIdentityKeyHash() { return vendorIdentityKeyHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
