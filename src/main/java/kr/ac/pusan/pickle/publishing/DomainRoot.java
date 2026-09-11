package kr.ac.pusan.pickle.publishing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A root this platform issues names under, and the organisation it belongs to.
 *
 * <p>The organisation is the reason this table exists. A name issued on its own
 * has no VM and no request behind it, so nothing on its path carries one, and
 * the administrator's listing is scoped on exactly that column. Reading it off
 * the root means the person issuing the name never has to know it — they pick
 * where the name lives, which they do understand, and the rest follows.</p>
 */
@Entity
@Table(name = "domain_roots")
public class DomainRoot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "root_domain", nullable = false, updatable = false)
    private String rootDomain;

    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected DomainRoot() {
    }

    public DomainRoot(String rootDomain, Long orgId) {
        this.rootDomain = rootDomain;
        this.orgId = orgId;
    }

    public Long getId() {
        return id;
    }

    public String getRootDomain() {
        return rootDomain;
    }

    public Long getOrgId() {
        return orgId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
