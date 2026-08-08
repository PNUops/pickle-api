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
    AUTO,
    PLATFORM,
    CUSTOM
}
