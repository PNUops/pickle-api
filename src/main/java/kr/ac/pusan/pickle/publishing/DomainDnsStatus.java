package kr.ac.pusan.pickle.publishing;

/**
 * Where the platform's own A record for a platform subdomain stands at the
 * DNS provider. NONE: no record was ever written, or it was removed. PENDING:
 * the desired state (a record while the domain serves, none once it stops)
 * has not been reconciled yet. APPLIED: the record was confirmed present.
 * FAILED: the last attempt failed and {@code dnsLastError} says why. Custom
 * domains live in the user's own zone and stay NONE for life.
 */
public enum DomainDnsStatus {
    NONE,
    PENDING,
    APPLIED,
    FAILED
}
