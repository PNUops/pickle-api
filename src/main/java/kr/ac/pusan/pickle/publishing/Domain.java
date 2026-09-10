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
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A domain (FQDN) owned by a workspace. Platform subdomains (AUTO/PLATFORM)
 * are ACTIVE on creation; custom domains carry a verification token and flow
 * PENDING→VERIFYING→ACTIVE via DNS polling.
 *
 * <p>The workspace is the owner; the VM, when there is one, is what the name
 * serves. Those were the same fact while every name served a VM, and reading
 * ownership off the VM is what made a name without one impossible to express.
 * Everything that asks who may see this row, which organisation scopes it or
 * who hears about it now asks the workspace.</p>
 */
@Entity
@Table(name = "domains")
public class Domain {

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

    /**
     * The owner. Every kind has one.
     *
     * <p>The database column is nullable for now and this mapping is what holds
     * the invariant. Tightening it in the same migration that adds it would
     * make the previous release's jar unable to insert here at all, which is
     * the one thing an automatic rollback must not land on; the NOT NULL
     * belongs to the release after the jar that writes the column is running.
     * Same for {@link #orgId}.</p>
     */
    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    /**
     * The organisation that administers this name. Held on the row rather than
     * read through the workspace, which carries none; {@code vms} and
     * {@code llm_api_keys} hold their own for the same reason.
     */
    @Column(name = "org_id", nullable = false, updatable = false)
    private Long orgId;

    /** The VM this name serves, when it serves one. Null for EXTERNAL. */
    @Column(name = "vm_id")
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

    /**
     * When the domain stopped serving. A platform subdomain keeps its row — and
     * so its claim on the FQDN — for a grace period after this, so the name is
     * not handed to someone else the moment it is released; the sweeper removes
     * it once the period passes. Null while the domain is serving.
     */
    @Column(name = "released_at")
    private Instant releasedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "domain_status")
    private DomainStatus status;

    /**
     * Where the platform's own A record for this name stands. Written only
     * through the {@code markDns*} methods so the three columns stay
     * consistent with each other (the schema checks the pairs); a custom
     * domain is never touched and stays NONE.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "dns_status", nullable = false, columnDefinition = "domain_dns_status")
    private DomainDnsStatus dnsStatus = DomainDnsStatus.NONE;

    @Column(name = "dns_last_error")
    private String dnsLastError;

    @Column(name = "dns_applied_at")
    private Instant dnsAppliedAt;

    /**
     * The deadline for its owner to say they still want this name. Only the
     * kinds nothing else expires carry one; a VM's name ends with the VM.
     */
    @Column(name = "renew_due_at")
    private Instant renewDueAt;

    /**
     * The revision of this domain's record set, in the same role
     * {@code routes.generation} plays for a vhost: an apply step re-reads it
     * under the name's lock and stands down when a newer intent has been
     * written since.
     */
    @Column(name = "records_generation", nullable = false)
    private long recordsGeneration;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Domain() {
    }

    private Domain(Long workspaceId, Long orgId, Long vmId, DomainKind kind, String fqdn,
            String rootDomain, String verificationToken, DomainStatus status) {
        this.workspaceId = workspaceId;
        this.orgId = orgId;
        this.vmId = vmId;
        this.kind = kind;
        this.fqdn = fqdn;
        this.rootDomain = rootDomain;
        this.verificationToken = verificationToken;
        this.status = status;
    }

    /**
     * Platform subdomain (AUTO/PLATFORM): ACTIVE immediately, no ownership
     * check. Its A record is owed from the start (PENDING) and the first apply
     * writes it.
     */
    public static Domain platform(Long workspaceId, Long orgId, Long vmId, DomainKind kind,
            String fqdn, String rootDomain) {
        Domain domain = new Domain(workspaceId, orgId, vmId, kind, fqdn, rootDomain, null,
                DomainStatus.ACTIVE);
        domain.dnsStatus = DomainDnsStatus.PENDING;
        return domain;
    }

    /** Custom domain: PENDING until TXT+A are verified, carrying the ownership token. */
    public static Domain custom(Long workspaceId, Long orgId, Long vmId, String fqdn,
            String verificationToken) {
        return new Domain(workspaceId, orgId, vmId, DomainKind.CUSTOM, fqdn, null,
                verificationToken, DomainStatus.PENDING);
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public Long getVmId() {
        return vmId;
    }

    public Instant getRenewDueAt() {
        return renewDueAt;
    }

    public void setRenewDueAt(Instant renewDueAt) {
        this.renewDueAt = renewDueAt;
    }

    public long getRecordsGeneration() {
        return recordsGeneration;
    }

    public void setRecordsGeneration(long recordsGeneration) {
        this.recordsGeneration = recordsGeneration;
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

    public Instant getReleasedAt() {
        return releasedAt;
    }

    public void setReleasedAt(Instant releasedAt) {
        this.releasedAt = releasedAt;
    }

    public DomainStatus getStatus() {
        return status;
    }

    public void setStatus(DomainStatus status) {
        this.status = status;
    }

    public DomainDnsStatus getDnsStatus() {
        return dnsStatus;
    }

    public String getDnsLastError() {
        return dnsLastError;
    }

    public Instant getDnsAppliedAt() {
        return dnsAppliedAt;
    }

    /**
     * The name is going into service and its record is owed: the next apply
     * ensures it. Set on every platform (re)attach, whatever the row held
     * before. A kind the proxy does not serve is left alone, whatever the
     * caller meant.
     */
    public void markDnsRecordOwed() {
        if (!kind.servedByPlatformProxy()) {
            return;
        }
        dnsStatus = DomainDnsStatus.PENDING;
        dnsLastError = null;
    }

    /**
     * The name is leaving service and whatever record exists is owed a
     * removal. A row at NONE stays NONE: the platform never wrote a record
     * for it (a row from before per-name records, on a zone the platform may
     * not even be able to write yet), so there is nothing to take down and
     * nothing to keep a removal retrying against an unconfigured provider.
     * A previous applied time is kept: the record may well still exist.
     */
    public void markDnsRemovalOwed() {
        if (!kind.servedByPlatformProxy() || dnsStatus == DomainDnsStatus.NONE) {
            return;
        }
        dnsStatus = DomainDnsStatus.PENDING;
        dnsLastError = null;
    }

    /** The provider confirmed the record is present. */
    public void markDnsApplied() {
        dnsStatus = DomainDnsStatus.APPLIED;
        dnsLastError = null;
        dnsAppliedAt = Instant.now();
    }

    /** The provider confirmed the record is gone (or none was ever written). */
    public void markDnsRemoved() {
        dnsStatus = DomainDnsStatus.NONE;
        dnsLastError = null;
        dnsAppliedAt = null;
    }

    /** The last attempt failed; the applied time survives because the record may. */
    public void markDnsFailed(String error) {
        dnsStatus = DomainDnsStatus.FAILED;
        dnsLastError = error != null && !error.isBlank() ? error : "원인 미상";
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
