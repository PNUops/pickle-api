package kr.ac.pusan.pickle.vm;

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
import org.hibernate.type.SqlTypes;

/**
 * Permanent per-VM history entry (docs/plan/02 vm_events, product brief §20).
 * Append-only: rows are inserted and read, never updated or deleted.
 * {@code actorId} is null for system-initiated events (sweeper, reconciler).
 */
@Entity
@Table(name = "vm_events")
public class VmEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vm_id", nullable = false)
    private Long vmId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "vm_event_type")
    private VmEventType type;

    @Column(name = "actor_id")
    private Long actorId;

    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VmEvent() {
    }

    public VmEvent(Long vmId, VmEventType type, Long actorId, String detail) {
        this.vmId = vmId;
        this.type = type;
        this.actorId = actorId;
        this.detail = detail;
    }

    public Long getId() {
        return id;
    }

    public Long getVmId() {
        return vmId;
    }

    public VmEventType getType() {
        return type;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
