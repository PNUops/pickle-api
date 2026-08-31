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

    @Column(name = "funding_reference")
    private @Nullable String fundingReference;

    @Column(name = "evidence_reference")
    private @Nullable String evidenceReference;

    @Column(name = "vendor_workspace_id")
    private @Nullable UUID vendorWorkspaceId;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected OpenRouterAccount() {
    }

    public OpenRouterAccount(long orgId, String name, @Nullable String fundingReference,
            @Nullable String evidenceReference, long createdBy) {
        this.orgId = orgId;
        this.name = name;
        this.fundingReference = fundingReference;
        this.evidenceReference = evidenceReference;
        this.createdBy = createdBy;
    }

    public void update(String name, @Nullable String fundingReference,
            @Nullable String evidenceReference, OpenRouterAccountStatus status, Instant now) {
        this.name = name;
        this.fundingReference = fundingReference;
        this.evidenceReference = evidenceReference;
        this.status = status;
        this.updatedAt = now;
    }

    public void discoverVendorWorkspace(UUID vendorWorkspaceId, Instant now) {
        if (this.vendorWorkspaceId != null && !this.vendorWorkspaceId.equals(vendorWorkspaceId)) {
            throw new IllegalStateException("OpenRouter vendor workspace is immutable");
        }
        this.vendorWorkspaceId = vendorWorkspaceId;
        this.updatedAt = now;
    }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public Long getOrgId() { return orgId; }
    public String getName() { return name; }
    public OpenRouterAccountStatus getStatus() { return status; }
    public @Nullable String getFundingReference() { return fundingReference; }
    public @Nullable String getEvidenceReference() { return evidenceReference; }
    public @Nullable UUID getVendorWorkspaceId() { return vendorWorkspaceId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
