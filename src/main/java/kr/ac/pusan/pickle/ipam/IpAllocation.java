package kr.ac.pusan.pickle.ipam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A single IP handed to a VM. Rows are only ever written by
 * {@link IpamService} through race-safe SQL (unique-ip insert / CAS update);
 * this entity is the read-side mapping.
 */
@Entity
@Table(name = "ip_allocations")
public class IpAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pool_id", nullable = false)
    private Long poolId;

    /** Host address without prefix, e.g. {@code 172.29.1.10}. Globally unique. */
    @JdbcTypeCode(SqlTypes.INET)
    @Column(nullable = false, unique = true, columnDefinition = "inet")
    private String ip;

    @Column(name = "vm_id")
    private Long vmId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "allocation_status")
    private AllocationStatus status = AllocationStatus.ALLOCATED;

    @Column(name = "allocated_at", nullable = false)
    private Instant allocatedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected IpAllocation() {
    }

    public Long getId() {
        return id;
    }

    public Long getPoolId() {
        return poolId;
    }

    public String getIp() {
        return ip;
    }

    public Long getVmId() {
        return vmId;
    }

    public AllocationStatus getStatus() {
        return status;
    }

    public Instant getAllocatedAt() {
        return allocatedAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }
}
