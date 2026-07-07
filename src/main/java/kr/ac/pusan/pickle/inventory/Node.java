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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Proxmox node (docs/plan/02). M2 maps the identity/capacity subset used for
 * VM placement and approval-context headroom; per-node config columns
 * (labels, vm_bridge, storage) stay unmapped until the M3 pipeline needs them.
 */
@Entity
@Table(name = "nodes")
public class Node {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    public String getName() {
        return name;
    }

    public String getApiHost() {
        return apiHost;
    }

    public NodeStatus getStatus() {
        return status;
    }

    public int getCpuThreads() {
        return cpuThreads;
    }

    public int getMemoryMb() {
        return memoryMb;
    }
}
