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
 *
 * <p>{@code autoApprove} is the second thing a root decides: whether a name
 * asked for under it is issued on the spot or waits for a reviewer. It sits
 * here rather than on the organisation because the name space is what the
 * decision is about — an institution running two roots may want one open and
 * one reviewed. Only external issuance reads it today; publishing a platform
 * subdomain for a VM does not, and the administrator's screen says so where
 * the value is set.</p>
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

    @Column(name = "auto_approve", nullable = false)
    private boolean autoApprove = true;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /**
     * Moved by hand on every write. The column has existed since the table did
     * and nothing could change a row until the policy became editable, so it
     * sat equal to {@code created_at} harmlessly; left unmapped now it would
     * claim to be the last time the policy changed and always be wrong.
     */
    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

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

    public boolean isAutoApprove() {
        return autoApprove;
    }

    public void setAutoApprove(boolean autoApprove) {
        this.autoApprove = autoApprove;
        this.updatedAt = Instant.now();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
