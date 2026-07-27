package kr.ac.pusan.pickle.publishing;

/**
 * Domain kind. REQUESTED = user-chosen platform subdomain (self-service),
 * CUSTOM = user-owned domain proven via DNS TXT, AUTO = legacy system-generated
 * subdomain — auto-generation was abolished with self-service publishing, so no
 * new AUTO row is ever written; the value stays for the DB enum and history.
 */
public enum DomainKind {
    AUTO,
    REQUESTED,
    CUSTOM
}
