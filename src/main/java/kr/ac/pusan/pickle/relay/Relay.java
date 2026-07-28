package kr.ac.pusan.pickle.relay;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A forwarding relay host (relays). The relay-side agent authenticates its
 * sync calls with a per-relay token (only the sha256 hash is stored; a null
 * hash means no token was issued yet and every sync fails closed) and is
 * additionally pinned to {@code sourceIp} — the relay's tunnel-side address,
 * the only TCP peer accepted for this relay's sync path.
 *
 * <p>{@code mappingGeneration} is bumped by {@link RelayGenerations} in the
 * same transaction as any mapping write for this relay (a per-relay counter —
 * deliberately not a global sequence). {@code appliedGeneration},
 * {@code lastContactAt}, {@code agentVersion} and {@code lastError} are
 * heartbeat state written by the sync endpoint; they are claims by the relay,
 * not measurements.</p>
 */
@Entity
@Table(name = "relays")
public class Relay {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "public_host")
    private String publicHost;

    @Column(name = "source_ip", nullable = false)
    private String sourceIp;

    /** sha256 hex of the sync token; null = not issued, auth fails closed. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", length = 64)
    private String tokenHash;

    @Column(name = "port_band_start", nullable = false)
    private int portBandStart;

    @Column(name = "port_band_end", nullable = false)
    private int portBandEnd;

    @Column(name = "mapping_generation", nullable = false)
    private long mappingGeneration;

    @Column(name = "applied_generation", nullable = false)
    private long appliedGeneration;

    @Column(name = "last_contact_at")
    private Instant lastContactAt;

    @Column(name = "contact_lost_since")
    private Instant contactLostSince;

    @Column(name = "agent_version")
    private String agentVersion;

    /** Sanitized JSON array of the agent-reported apply errors, or null. */
    @Column(name = "last_error")
    private String lastError;

    @Column(nullable = false)
    private boolean enabled;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Relay() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getPublicHost() {
        return publicHost;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public void setTokenHash(String tokenHash) {
        this.tokenHash = tokenHash;
    }

    public int getPortBandStart() {
        return portBandStart;
    }

    public int getPortBandEnd() {
        return portBandEnd;
    }

    /** Inclusive band size (candidate public-port count). */
    public int bandSize() {
        return portBandEnd - portBandStart + 1;
    }

    public long getMappingGeneration() {
        return mappingGeneration;
    }

    public long getAppliedGeneration() {
        return appliedGeneration;
    }

    public Instant getLastContactAt() {
        return lastContactAt;
    }

    public Instant getContactLostSince() {
        return contactLostSince;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public String getLastError() {
        return lastError;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
