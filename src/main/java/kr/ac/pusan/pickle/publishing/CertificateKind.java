package kr.ac.pusan.pickle.publishing;

/**
 * Certificate kind: the shared per-root platform wildcard vs a per-domain
 * Let's Encrypt cert. {@code ORIGIN_CA_WILDCARD} is the database enum label
 * from when the wildcard was a CDN origin certificate; the material behind it
 * is a Let's Encrypt wildcard now, and the label stays until it is renamed
 * on its own.
 */
public enum CertificateKind {
    ORIGIN_CA_WILDCARD,
    LETS_ENCRYPT
}
