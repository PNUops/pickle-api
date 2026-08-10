package kr.ac.pusan.pickle.inventory;

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
 * Proxmox node: identity/capacity for placement and headroom,
 * plus the per-node config the provision pipeline reads (vm_bridge, storage,
 * ip_pool_id — never hardcoded). Only {@code labels} stays unmapped for now.
 */
@Entity
@Table(name = "nodes")
public class Node {

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

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "api_host", nullable = false)
    private String apiHost;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "node_status")
    private NodeStatus status = NodeStatus.ACTIVE;

    @Column(name = "cpu_threads", nullable = false)
    private int cpuThreads;

    @Column(name = "memory_mb", nullable = false)
    private int memoryMb;

    /** Bridge VM NICs attach to, e.g. {@code vmbr2}. */
    @Column(name = "vm_bridge", nullable = false)
    private String vmBridge;

    /** Proxmox storage id clones land on, e.g. {@code local-lvm}. */
    @Column(nullable = false)
    private String storage;

    /** IP pool VMs on this node allocate from (FK added by V5). */
    @Column(name = "ip_pool_id")
    private Long ipPoolId;

    /**
     * Thin-pool size in GB, measured on the host by an operations tool; null
     * until measured (V76).
     */
    @Column(name = "disk_capacity_gb")
    private Long diskCapacityGb;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Node() {
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

    public String getApiHost() {
        return apiHost;
    }

    public NodeStatus getStatus() {
        return status;
    }

    /**
     * Admin status transition (contract v0.21.0). Placement only picks ACTIVE
     * nodes, so MAINTENANCE/OFFLINE excludes the node from new VMs without
     * touching existing guests.
     */
    public void setStatus(NodeStatus status) {
        this.status = status;
    }

    public int getCpuThreads() {
        return cpuThreads;
    }

    public int getMemoryMb() {
        return memoryMb;
    }

    public String getVmBridge() {
        return vmBridge;
    }

    public String getStorage() {
        return storage;
    }

    public Long getIpPoolId() {
        return ipPoolId;
    }

    /**
     * Physical size of the thin pool guest disks are carved out of. Null on a
     * node nobody has measured yet, and advisory even when set: the pool is
     * over-provisioned, so allocation may exceed it legitimately.
     */
    public Long getDiskCapacityGb() {
        return diskCapacityGb;
    }
}
