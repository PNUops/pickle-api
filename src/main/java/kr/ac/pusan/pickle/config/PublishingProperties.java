package kr.ac.pusan.pickle.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP publishing settings ({@code pickle.publishing.*}, docs/plan/06).
 *
 * @param proxyPublicIp     the reverse-proxy public IPv4 a custom domain's A
 *                          record must point at (verification target). Defaults
 *                          to the campus proxy address from plan/06.
 * @param originCaCertRef   certRef the proxy-agent maps to the shared Origin CA
 *                          wildcard for platform subdomains (docs/api/internal.md)
 * @param letsEncryptCertRef certRef the proxy-agent maps to a per-domain LE cert
 *                          for custom domains
 * @param leCertValidityDays modelled validity of a freshly issued LE cert
 *                          (notAfter = now + this) — the real value is reported by
 *                          the agent's status endpoint in a later slice
 * @param verificationTimeout how long the recurring DNS scan keeps re-checking an
 *                          unverified custom domain before parking it FAILED
 *                          (default 72h; a manual verify still succeeds any time
 *                          the records finally match)
 */
@ConfigurationProperties(prefix = "pickle.publishing")
public record PublishingProperties(
        String proxyPublicIp,
        String originCaCertRef,
        String letsEncryptCertRef,
        Integer leCertValidityDays,
        Duration verificationTimeout) {

    public PublishingProperties {
        proxyPublicIp = proxyPublicIp != null && !proxyPublicIp.isBlank()
                ? proxyPublicIp : "164.125.249.87";
        originCaCertRef = originCaCertRef != null && !originCaCertRef.isBlank()
                ? originCaCertRef : "origin-wildcard";
        letsEncryptCertRef = letsEncryptCertRef != null && !letsEncryptCertRef.isBlank()
                ? letsEncryptCertRef : "letsencrypt";
        leCertValidityDays = leCertValidityDays != null ? leCertValidityDays : 90;
        verificationTimeout = verificationTimeout != null ? verificationTimeout
                : Duration.ofHours(72);
    }
}
