package kr.ac.pusan.pickle.ipam;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * IP pool a node's VMs allocate from. Rows are seeded by
 * migration / managed by operators; the application only reads them, so the
 * PostgreSQL-native columns (cidr, inet, jsonb) are mapped as plain strings
 * and parsed in {@link IpamService}.
 */
@Entity
@Table(name = "ip_pools")
public class IpPool {

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

    /** IPv4 network in CIDR notation, e.g. {@code 172.29.0.0/16}. */
    @Column(nullable = false, columnDefinition = "cidr")
    private String cidr;

    @JdbcTypeCode(SqlTypes.INET)
    @Column(nullable = false, columnDefinition = "inet")
    private String gateway;

    /** JSON array of nameserver addresses, e.g. {@code ["8.8.8.8"]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String dns;

    /** JSON array of inclusive ranges: {@code [{"from": "...", "to": "..."}]}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reserved_ranges", nullable = false, columnDefinition = "jsonb")
    private String reservedRanges;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IpPool() {
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

    public String getCidr() {
        return cidr;
    }

    public String getGateway() {
        return gateway;
    }

    public String getDns() {
        return dns;
    }

    public String getReservedRanges() {
        return reservedRanges;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
