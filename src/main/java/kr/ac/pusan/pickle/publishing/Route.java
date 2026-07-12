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
 * A published HTTP route for a domain (docs/plan/02 routes, docs/plan/06). One
 * live route per domain in v1. {@code generation} is a DB-owned monotonic token
 * (route_generation_seq) bumped on every desired-state change so a stale apply
 * can never clobber a newer one (docs/api/internal.md Link 2); {@code applied*}
 * mirror what proxy-agent last confirmed.
 */
@Entity
@Table(name = "routes")
public class Route {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "domain_id", nullable = false)
    private Long domainId;

    @Column(name = "target_port", nullable = false)
    private int targetPort;

    /** v1: always {@code HTTP} (upstream protocol; HTTPS passthrough is roadmap). */
    @Column(nullable = false)
    private String protocol = "HTTP";

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "route_status")
    private RouteStatus status;

    @Column(nullable = false)
    private long generation;

    @Column(name = "applied_generation")
    private Long appliedGeneration;

    @Column(name = "applied_at")
    private Instant appliedAt;

    @Column(name = "last_error")
    private String lastError;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Route() {
    }

    public Route(Long domainId, int targetPort, long generation) {
        this.domainId = domainId;
        this.targetPort = targetPort;
        this.generation = generation;
        this.status = RouteStatus.PENDING;
    }

    public Long getId() {
        return id;
    }

    public Long getDomainId() {
        return domainId;
    }

    public int getTargetPort() {
        return targetPort;
    }

    public void setTargetPort(int targetPort) {
        this.targetPort = targetPort;
    }

    public String getProtocol() {
        return protocol;
    }

    public RouteStatus getStatus() {
        return status;
    }

    public void setStatus(RouteStatus status) {
        this.status = status;
    }

    public long getGeneration() {
        return generation;
    }

    public void setGeneration(long generation) {
        this.generation = generation;
    }

    public Long getAppliedGeneration() {
        return appliedGeneration;
    }

    public void setAppliedGeneration(Long appliedGeneration) {
        this.appliedGeneration = appliedGeneration;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Instant appliedAt) {
        this.appliedAt = appliedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
