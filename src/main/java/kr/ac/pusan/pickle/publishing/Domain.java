package kr.ac.pusan.pickle.publishing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A domain (FQDN) attached to a VM. Platform
 * subdomains (AUTO/REQUESTED) are ACTIVE on creation; custom domains carry a
 * verification token and flow PENDING→VERIFYING→ACTIVE via DNS polling.
 */
@Entity
@Table(name = "domains")
public class Domain {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vm_id", nullable = false)
    private Long vmId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "domain_kind")
    private DomainKind kind;

    @Column(nullable = false)
    private String fqdn;

    @Column(name = "root_domain")
    private String rootDomain;

    @Column(name = "verification_token")
    private String verificationToken;

    @Column(name = "a_verified", nullable = false)
    private boolean aVerified;

    @Column(name = "txt_verified", nullable = false)
    private boolean txtVerified;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "verified_at")
    private Instant verifiedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "domain_status")
    private DomainStatus status;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Domain() {
    }

    private Domain(Long vmId, DomainKind kind, String fqdn, String rootDomain,
            String verificationToken, DomainStatus status) {
        this.vmId = vmId;
        this.kind = kind;
        this.fqdn = fqdn;
        this.rootDomain = rootDomain;
        this.verificationToken = verificationToken;
        this.status = status;
    }

    /** Platform subdomain (AUTO/REQUESTED): ACTIVE immediately, no ownership check. */
    public static Domain platform(Long vmId, DomainKind kind, String fqdn, String rootDomain) {
        return new Domain(vmId, kind, fqdn, rootDomain, null, DomainStatus.ACTIVE);
    }

    /** Custom domain: PENDING until TXT+A are verified, carrying the ownership token. */
    public static Domain custom(Long vmId, String fqdn, String verificationToken) {
        return new Domain(vmId, DomainKind.CUSTOM, fqdn, null, verificationToken, DomainStatus.PENDING);
    }

    public Long getId() {
        return id;
    }

    public Long getVmId() {
        return vmId;
    }

    public DomainKind getKind() {
        return kind;
    }

    public String getFqdn() {
        return fqdn;
    }

    public String getRootDomain() {
        return rootDomain;
    }

    public String getVerificationToken() {
        return verificationToken;
    }

    public boolean isAVerified() {
        return aVerified;
    }

    public void setAVerified(boolean aVerified) {
        this.aVerified = aVerified;
    }

    public boolean isTxtVerified() {
        return txtVerified;
    }

    public void setTxtVerified(boolean txtVerified) {
        this.txtVerified = txtVerified;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void setLastCheckedAt(Instant lastCheckedAt) {
        this.lastCheckedAt = lastCheckedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(Instant verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public DomainStatus getStatus() {
        return status;
    }

    public void setStatus(DomainStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
