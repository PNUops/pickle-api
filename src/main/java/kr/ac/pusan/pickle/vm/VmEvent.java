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
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Permanent per-VM history entry (vm_events).
 * Append-only: rows are inserted and read, never updated or deleted.
 * {@code actorId} is null for system-initiated events (sweeper, reconciler),
 * which is the same fact {@code actorKind} carries as {@link VmActorKind#SYSTEM}
 * — a check constraint keeps the two from disagreeing.
 *
 * <p>There is one constructor and it takes the kind, so every call site has to
 * say which surface it is: a default would let a new administrator action be
 * recorded as a member's and nothing would notice.
 */
@Entity
@Table(name = "vm_events")
public class VmEvent {

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

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "vm_event_type")
    private VmEventType type;

    @Column(name = "actor_id")
    private Long actorId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "actor_kind", nullable = false, columnDefinition = "vm_actor_kind")
    private VmActorKind actorKind;

    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected VmEvent() {
    }

    public VmEvent(Long vmId, VmEventType type, Long actorId, VmActorKind actorKind, String detail) {
        this.vmId = vmId;
        this.type = type;
        this.actorId = actorId;
        this.actorKind = actorKind;
        this.detail = detail;
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

    public VmEventType getType() {
        return type;
    }

    public Long getActorId() {
        return actorId;
    }

    public VmActorKind getActorKind() {
        return actorKind;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
