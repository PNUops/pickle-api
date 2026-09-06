package kr.ac.pusan.pickle.publishing.dns;

/**
 * A DNS provider call did not do what was asked: transport failure, a
 * rejected token, a refused change. The message is what lands in
 * {@code domains.dns_last_error}, so it is written for an operator reading
 * the admin listing, with the provider's own words where it gave any.
 */
public class DnsProviderException extends RuntimeException {

    public DnsProviderException(String message) {
        super(message);
    }

    public DnsProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
