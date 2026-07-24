package kr.ac.pusan.pickle.publishing;

/**
 * Domain kind. AUTO = system-generated subdomain, REQUESTED =
 * admin-granted subdomain, CUSTOM = user-owned domain proven via DNS TXT.
 */
public enum DomainKind {
    AUTO,
    REQUESTED,
    CUSTOM
}
