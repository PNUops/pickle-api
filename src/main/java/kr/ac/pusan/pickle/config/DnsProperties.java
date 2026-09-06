package kr.ac.pusan.pickle.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DNS record provider settings ({@code pickle.dns.*}) — the platform writes
 * one A record per platform subdomain and this says where.
 *
 * <p>Fail-closed, not fail-fast, the same posture as Google sign-in: nothing
 * here stops the api from booting. Under the default {@code none} provider,
 * and under {@code google} with a missing project, zone or key file, the
 * provider reports itself unconfigured, platform-subdomain publishing is
 * refused with a clear 409, and everything unrelated keeps working. Dev and
 * test run the {@code noop} provider, which accepts every write and touches
 * nothing.</p>
 *
 * @param provider      {@code none} (default: fail closed), {@code noop}
 *                      (dev/test: accept and do nothing) or {@code google}
 *                      (Google Cloud DNS)
 * @param google        Google Cloud DNS settings, read only under {@code google}
 * @param recordTtl     TTL written on every platform A record (default 5m)
 * @param pruneOrphans  whether the admin resync may DELETE zone records the
 *                      platform no longer claims (default false: it only logs
 *                      what it would remove)
 * @param connectTimeout TCP connect timeout for the provider API (default 5s)
 * @param readTimeout    per-request read timeout for the provider API (default 20s)
 */
@ConfigurationProperties(prefix = "pickle.dns")
public record DnsProperties(
        String provider,
        Google google,
        Duration recordTtl,
        Boolean pruneOrphans,
        Duration connectTimeout,
        Duration readTimeout) {

    public static final String PROVIDER_NONE = "none";
    public static final String PROVIDER_NOOP = "noop";
    public static final String PROVIDER_GOOGLE = "google";

    public DnsProperties {
        provider = provider != null && !provider.isBlank()
                ? provider.strip().toLowerCase(java.util.Locale.ROOT) : PROVIDER_NONE;
        google = google != null ? google : new Google(null, null, null);
        recordTtl = recordTtl != null ? recordTtl : Duration.ofMinutes(5);
        pruneOrphans = pruneOrphans != null ? pruneOrphans : Boolean.FALSE;
        connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(5);
        readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(20);
    }

    /**
     * @param project     Google Cloud project id that owns the managed zone
     * @param zone        managed zone name (the resource name, not the DNS name)
     * @param credentials path of the service-account key file (JSON); the
     *                    value is never read from the environment itself
     */
    public record Google(String project, String zone, String credentials) {

        public Google {
            credentials = credentials != null && !credentials.isBlank()
                    ? credentials : "/etc/pickle/gcp-dns.json";
        }

        /** Whether every value the client needs to make a call is present. */
        public boolean complete() {
            return project != null && !project.isBlank() && zone != null && !zone.isBlank();
        }
    }
}
