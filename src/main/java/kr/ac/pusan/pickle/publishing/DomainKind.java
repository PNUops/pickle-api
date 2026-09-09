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
    AUTO(true),
    PLATFORM(true),
    CUSTOM(false);

    private final boolean servedByPlatformProxy;

    DomainKind(boolean servedByPlatformProxy) {
        this.servedByPlatformProxy = servedByPlatformProxy;
    }

    /**
     * Whether this kind's names are served by the platform's own reverse proxy:
     * the platform writes their A record to point at it, renders them a vhost,
     * and covers their TLS with the root's wildcard certificate.
     *
     * <p>This is a list of the kinds that opt in, not a test that excludes
     * CUSTOM, and the difference is the point. The rule was spelled
     * {@code kind != CUSTOM} in four places, which reads as "the platform
     * serves every name in its own zone" — true while it did, and false for a
     * kind that lives in this zone and points somewhere else. A kind added
     * later is outside this until it says otherwise, so forgetting to look here
     * costs a record that does not get written rather than a user's records
     * overwritten with the proxy address, or a certificate reported for a name
     * the platform never serves.</p>
     */
    public boolean servedByPlatformProxy() {
        return servedByPlatformProxy;
    }
}
