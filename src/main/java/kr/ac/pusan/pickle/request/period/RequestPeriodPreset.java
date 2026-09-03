package kr.ac.pusan.pickle.request.period;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import kr.ac.pusan.pickle.inventory.CatalogStatus;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * A usage period the request form offers (request_period_presets, V105).
 *
 * <p>Rows are operator policy against a real calendar, not seed data: a term
 * ends on a date somebody has to look up, and next year's date is different.
 * The admin console owns the write path, the same arrangement
 * {@code vm_flavors} has and for the same reason.</p>
 *
 * <p>Every offered period ends. A resource that must not expire is asked for
 * on the form itself, which leaves {@code requests.req_end_date} null; keeping
 * this catalogue dated means no row here can quietly become the one that grants
 * an unending resource.</p>
 */
@Entity
@Table(name = "request_period_presets")
public class RequestPeriodPreset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The identifier this row wears outside the API boundary. */
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "public_id", nullable = false, updatable = false, unique = true)
    private UUID publicId = UUID.randomUUID();

    @Column(nullable = false)
    private String name;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "catalog_status")
    private CatalogStatus status = CatalogStatus.ACTIVE;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RequestPeriodPreset() {
    }

    public RequestPeriodPreset(String name, String displayName, LocalDate endDate,
            CatalogStatus status, int displayOrder) {
        this.name = name;
        this.displayName = displayName;
        this.endDate = endDate;
        this.status = status;
        this.displayOrder = displayOrder;
    }

    /** Whether this row still names a period a new request could sit inside. */
    public boolean isOfferableOn(LocalDate today) {
        return status == CatalogStatus.ACTIVE && !endDate.isBefore(today);
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getName() {
        return name;
    }

    public String getDisplayName() {
        return displayName;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public CatalogStatus getStatus() {
        return status;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    // Admin management writes through these — a period must never be a DB-only
    // state (the write path ships with the field).
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public void setStatus(CatalogStatus status) {
        this.status = status;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}
