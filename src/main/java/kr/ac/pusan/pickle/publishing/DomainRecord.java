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
import java.util.List;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordType;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * One record set a domain's owner has asked the platform to hold in its zone.
 *
 * <p>A row is a set, not a value: the provider takes (name, type, values, ttl)
 * and answers for that whole thing, so this is the unit that carries a status,
 * an error and an applied time. Two rows of one set could otherwise disagree
 * about whether the set is in the zone, which is a state DNS cannot be in.
 */
@Entity
@Table(name = "domain_records")
public class DomainRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "domain_id", nullable = false, updatable = false)
    private Long domainId;

    /**
     * Relative to the domain's own name: empty is the name itself, {@code www}
     * is one label under it. Stored relative so the row says what its owner
     * typed, and so nothing here can name a zone the owner does not hold.
     */
    @Column(nullable = false, updatable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, updatable = false, columnDefinition = "domain_record_type")
    private DnsRecordType type;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private String[] rrdatas;

    @Column(nullable = false)
    private int ttl;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "domain_record_status")
    private DomainRecordStatus status = DomainRecordStatus.PENDING;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DomainRecord() {
    }

    public DomainRecord(Long domainId, String name, DnsRecordType type, List<String> rrdatas,
            int ttl) {
        this.domainId = domainId;
        this.name = name;
        this.type = type;
        this.rrdatas = rrdatas.toArray(String[]::new);
        this.ttl = ttl;
    }

    public Long getId() {
        return id;
    }

    public Long getDomainId() {
        return domainId;
    }

    public String getName() {
        return name;
    }

    public DnsRecordType getType() {
        return type;
    }

    /** The set's values, in order. Copied out so the stored array is not aliased. */
    public List<String> getRrdatas() {
        return List.of(rrdatas);
    }

    public int getTtl() {
        return ttl;
    }

    public DomainRecordStatus getStatus() {
        return status;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    /**
     * Replaces what the set should hold. The name and type are the set's
     * identity and never change; an edit that moves either is a different set,
     * so it arrives as a removal and an addition.
     */
    public void reviseTo(List<String> rrdatas, int ttl) {
        this.rrdatas = rrdatas.toArray(String[]::new);
        this.ttl = ttl;
        markOwed();
    }

    /** The set is owed a write: the next apply makes the zone match it. */
    public void markOwed() {
        this.status = DomainRecordStatus.PENDING;
        this.lastError = null;
    }

    /**
     * The set is owed a removal. A row that was never applied is dropped
     * outright by the caller instead — there is nothing in the zone to take
     * down, and a removal that retries against a set that never existed would
     * never settle.
     */
    public void markRemovalOwed() {
        this.status = DomainRecordStatus.REMOVED;
        this.lastError = null;
    }

    public void markApplied(Instant now) {
        this.status = DomainRecordStatus.APPLIED;
        this.lastError = null;
        this.appliedAt = now;
    }

    /**
     * The last push of this set failed.
     *
     * <p>A set on its way out keeps REMOVED. FAILED means the set is owed a
     * WRITE, so a failed removal recorded as FAILED has the next apply put the
     * set back into the zone instead of taking it down — and since the row
     * only goes once the zone confirms the deletion, it would then be owed a
     * write forever and the name it belongs to could never be reclaimed. The
     * error is recorded either way; only the direction of the retry is at
     * stake here.</p>
     */
    public void markFailed(String error) {
        if (status != DomainRecordStatus.REMOVED) {
            this.status = DomainRecordStatus.FAILED;
        }
        this.lastError = error;
    }
}
