package kr.ac.pusan.pickle.publishing;

/**
 * Domain kind. PLATFORM = user-chosen platform subdomain (self-service),
 * CUSTOM = user-owned domain proven via DNS TXT, AUTO = legacy system-generated
 * subdomain — auto-generation was abolished with self-service publishing, so no
 * new AUTO row is ever written; the value stays for the DB enum and history.
 *
 * <p>PLATFORM was called REQUESTED back when the subdomain was written on the VM
 * request form. The form lost its domain axis, so the name described a step that
 * no longer happens instead of what the row actually holds, a platform subdomain
 * the user picks at publish time.</p>
 */
public enum DomainKind {
    AUTO(true, true),
    PLATFORM(true, true),
    CUSTOM(false, false);

    private final boolean servedByPlatformProxy;
    private final boolean reservesNameAfterRelease;

    DomainKind(boolean servedByPlatformProxy, boolean reservesNameAfterRelease) {
        this.servedByPlatformProxy = servedByPlatformProxy;
        this.reservesNameAfterRelease = reservesNameAfterRelease;
    }

    /**
     * Whether this kind's names are served by the platform's own reverse proxy:
     * the platform writes their A record to point at it, renders them a vhost,
     * and covers their TLS with the root's wildcard certificate.
     *
     * <p>This is a list of the kinds that opt in, not a test that excludes
     * CUSTOM, and the difference is the point. The rule was spelled
     * {@code kind != CUSTOM} at each of these decisions, which reads as "the
     * platform serves every name in its own zone" — true while it did, and
     * false for a kind that lives in this zone and points somewhere else. A
     * kind added later is outside this until it says otherwise, so forgetting
     * to look here costs a record that does not get written rather than a
     * user's records overwritten with the proxy address, or a certificate
     * reported for a name the platform never serves.</p>
     *
     * <p>Not the same question as who writes the name's records. A kind can
     * live in a zone this platform writes and still be served somewhere else;
     * this asks only whether the reverse proxy is the answer.</p>
     */
    public boolean servedByPlatformProxy() {
        return servedByPlatformProxy;
    }

    /**
     * Whether releasing this kind's name holds it for its owner through a grace
     * period instead of freeing it at once.
     *
     * <p>A separate question from {@link #servedByPlatformProxy()} even though
     * the two answer alike today. The grace exists because a name in a shared
     * space cannot be un-mistaken once somebody else takes it, and a name in
     * the user's own zone has no such problem — which is about who owns the
     * name space, not about who serves the name. Splitting them is what keeps
     * a kind from landing in the state the single test used to make
     * unreachable: outside the per-VM cap and inside the reservation grace, or
     * the reverse.</p>
     */
    public boolean reservesNameAfterRelease() {
        return reservesNameAfterRelease;
    }
}
