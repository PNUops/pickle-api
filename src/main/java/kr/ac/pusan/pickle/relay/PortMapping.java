package kr.ac.pusan.pickle.relay;

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
import org.hibernate.annotations.UpdateTimestamp;

/**
 * One desired relay DNAT rule (port_mappings): relay {@code publicPort} →
 * the VM's live address:{@code targetPort}. The target ADDRESS is deliberately
 * not a column — it is resolved from the VM's own ALLOCATED
 * {@code ip_allocations} row at snapshot-read time, so a released or
 * re-assigned IP can never linger inside a mapping.
 *
 * <p>{@code lastChangeGeneration} records the relay generation of this row's
 * last change (creation or edit — not creation only, or an edited mapping
 * would show active with stale rules); the derived apply state is ACTIVE iff
 * the relay's {@code appliedGeneration} has caught up to it. Guard columns are
 * per-mapping overrides for the agent's connection guards: null = agent
 * default, 0 = guard disabled, &gt;0 = explicit limit.</p>
 */
@Entity
@Table(name = "port_mappings")
public class PortMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "relay_id", nullable = false)
    private Long relayId;

    @Column(name = "vm_id", nullable = false)
    private Long vmId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PortMappingProto proto;

    @Column(name = "public_port", nullable = false)
    private int publicPort;

    @Column(name = "target_port", nullable = false)
    private int targetPort;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PortMappingStatus status;

    @Column(name = "suspended_reason")
    private String suspendedReason;

    /** Suspending admin; null for the threshold auto-suspend. */
    @Column(name = "suspended_by")
    private Long suspendedBy;

    @Column(name = "last_change_generation", nullable = false)
    private long lastChangeGeneration;

    @Column(name = "ct_max")
    private Integer ctMax;

    @Column(name = "new_conn_rate")
    private Integer newConnRate;

    @Column(name = "new_conn_burst")
    private Integer newConnBurst;

    @Column(name = "per_source_rate")
    private Integer perSourceRate;

    @Column(name = "per_source_burst")
    private Integer perSourceBurst;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PortMapping() {
    }

    public Long getId() {
        return id;
    }

    public Long getRelayId() {
        return relayId;
    }

    public Long getVmId() {
        return vmId;
    }

    public PortMappingProto getProto() {
        return proto;
    }

    public int getPublicPort() {
        return publicPort;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public PortMappingStatus getStatus() {
        return status;
    }

    public void setStatus(PortMappingStatus status) {
        this.status = status;
    }

    public String getSuspendedReason() {
        return suspendedReason;
    }

    public void setSuspendedReason(String suspendedReason) {
        this.suspendedReason = suspendedReason;
    }

    public Long getSuspendedBy() {
        return suspendedBy;
    }

    public void setSuspendedBy(Long suspendedBy) {
        this.suspendedBy = suspendedBy;
    }

    public long getLastChangeGeneration() {
        return lastChangeGeneration;
    }

    public void setLastChangeGeneration(long lastChangeGeneration) {
        this.lastChangeGeneration = lastChangeGeneration;
    }

    public Integer getCtMax() {
        return ctMax;
    }

    public void setCtMax(Integer ctMax) {
        this.ctMax = ctMax;
    }

    public Integer getNewConnRate() {
        return newConnRate;
    }

    public void setNewConnRate(Integer newConnRate) {
        this.newConnRate = newConnRate;
    }

    public Integer getNewConnBurst() {
        return newConnBurst;
    }

    public void setNewConnBurst(Integer newConnBurst) {
        this.newConnBurst = newConnBurst;
    }

    public Integer getPerSourceRate() {
        return perSourceRate;
    }

    public void setPerSourceRate(Integer perSourceRate) {
        this.perSourceRate = perSourceRate;
    }

    public Integer getPerSourceBurst() {
        return perSourceBurst;
    }

    public void setPerSourceBurst(Integer perSourceBurst) {
        this.perSourceBurst = perSourceBurst;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
