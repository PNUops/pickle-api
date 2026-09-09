package kr.ac.pusan.pickle.publishing;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.config.PublishingProperties;
import kr.ac.pusan.pickle.publishing.dto.CertificateView;
import kr.ac.pusan.pickle.publishing.dto.DomainDetailView;
import kr.ac.pusan.pickle.publishing.dto.DomainSummaryView;
import kr.ac.pusan.pickle.publishing.dto.DomainVerificationView;
import kr.ac.pusan.pickle.publishing.dto.PublicationView;
import kr.ac.pusan.pickle.publishing.dto.RouteView;
import kr.ac.pusan.pickle.settings.SettingsService;
import org.springframework.stereotype.Component;

/**
 * Assembles the contract publish views (DomainDetail, PublicationView) from the
 * domain/route/certificate rows. Custom domains carry a verification block (the
 * A + TXT records to create and the current polling state); platform subdomains
 * do not (null). The certificate is the per-domain LE cert for custom domains and
 * the shared wildcard of the subdomain's own root for platform subdomains.
 */
@Component
public class PublicationAssembler {

    /** TXT ownership record prefix ({@code _pickle-verify.<fqdn>}). */
    public static final String VERIFY_RECORD_PREFIX = "_pickle-verify.";

    /**
     * Prefix of the certRef that selects a platform wildcard; the domain's root
     * follows it verbatim ({@code wildcard:example.dev}).
     */
    public static final String WILDCARD_CERT_REF_PREFIX = "wildcard:";

    /** The wildcard scope a platform root's certificate row carries. */
    public static String wildcardScope(String rootDomain) {
        return "*." + rootDomain;
    }

    private final RouteRepository routeRepository;
    private final CertificateRepository certificateRepository;
    private final PublishingProperties properties;
    private final SettingsService settingsService;

    public PublicationAssembler(RouteRepository routeRepository,
            CertificateRepository certificateRepository, PublishingProperties properties,
            SettingsService settingsService) {
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.properties = properties;
        this.settingsService = settingsService;
    }

    public DomainSummaryView toDomainSummary(Domain domain, UUID vmId) {
        return new DomainSummaryView(domain.getPublicId(), vmId, domain.getKind(),
                domain.getFqdn(), domain.getRootDomain(), domain.getStatus(),
                domain.getVerifiedAt(), domain.getReleasedAt(), reservedUntil(domain),
                domain.getCreatedAt(), domain.getDnsStatus(), domain.getDnsLastError(),
                domain.getDnsAppliedAt());
    }

    public DomainDetailView toDomainDetail(Domain domain, UUID vmId) {
        DomainVerificationView verification = domain.getKind() == DomainKind.CUSTOM
                ? verification(domain) : null;
        return new DomainDetailView(domain.getPublicId(), vmId, domain.getKind(),
                domain.getFqdn(), domain.getRootDomain(), domain.getStatus(),
                domain.getVerifiedAt(), domain.getReleasedAt(), reservedUntil(domain),
                domain.getCreatedAt(), domain.getDnsStatus(), domain.getDnsLastError(),
                domain.getDnsAppliedAt(), verification);
    }

    /**
     * When the released name stops being reserved — computed server-side
     * (releasedAt + the grace setting) so the console never re-derives it from
     * a setting it cannot read. A released custom row carries no grace under
     * the reservation policy: its {@code reservedUntil} equals its release
     * time (due immediately).
     *
     * <p>Package-private because the admin domain listing carries the same
     * axis: two copies of this arithmetic would let the two views disagree
     * about when a name comes free, and only one of them would be the one the
     * sweeper actually follows.</p>
     */
    Instant reservedUntil(Domain domain) {
        if (domain.getReleasedAt() == null) {
            return null;
        }
        if (domain.getKind() == DomainKind.CUSTOM) {
            return domain.getReleasedAt();
        }
        int graceDays = settingsService.integer(SettingsService.PLATFORM_SUBDOMAIN_RESERVE_DAYS,
                SubdomainPolicy.DEFAULT_RESERVE_DAYS);
        return domain.getReleasedAt().plus(graceDays, ChronoUnit.DAYS);
    }

    /** The full publish view for a domain — its live route and certificate. */
    public PublicationView toPublication(Domain domain, UUID vmId) {
        RouteView route = routeRepository.findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                .map(RouteView::from)
                .orElse(null);
        CertificateView certificate = certificateFor(domain).map(CertificateView::from).orElse(null);
        return new PublicationView(domain.getFqdn(), toDomainDetail(domain, vmId), route, certificate);
    }

    /**
     * Whether the domain has a live (non-REMOVED) route — i.e. is actually
     * published. A custom-domain row kept by unpublish (verification state
     * preserved) has none and must NOT surface as a publication: the contract
     * requires {@code PublicationView.route}.
     */
    public boolean hasLiveRoute(Domain domain) {
        return routeRepository.findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                .isPresent();
    }

    /**
     * The certificate backing a domain: its root's wildcard for the kinds the
     * platform proxy serves, else the domain's own row. Asked the positive way
     * round, so a kind the platform does not serve reports whatever certificate
     * it actually has (none, for a name whose TLS is somebody else's) instead of
     * inheriting a wildcard that does not cover it in any useful sense.
     */
    public Optional<Certificate> certificateFor(Domain domain) {
        if (!domain.getKind().servedByPlatformProxy()) {
            return certificateRepository.findFirstByDomainIdAndStatusNot(domain.getId(),
                    CertificateStatus.REVOKED);
        }
        return certificateRepository.findLiveWildcard(CertificateKind.ORIGIN_CA_WILDCARD,
                wildcardScope(domain.getRootDomain()));
    }

    /**
     * The certRef the proxy-agent resolves to certificate material for this
     * domain: the wildcard of its own root for a name the platform proxy
     * serves, a per-domain Let's Encrypt cert for a custom domain. Deriving the
     * platform ref from the root, rather than from a single configured
     * constant, is what lets a second root domain be introduced with no code
     * change: the agent gains a certificate, this gains nothing. An unknown
     * root reaches the agent as an unresolvable ref and is refused there, so a
     * misconfigured root cannot be rendered with some other root's certificate.
     *
     * <p>Every other kind throws rather than falling through to the Let's
     * Encrypt ref. That ref makes the agent drive certbot for the name, and
     * handing it a name inside a platform root is the 2026-07-30 accident: a
     * publicly issued certificate for a platform subdomain, which
     * {@code nginx -t} accepts and nobody notices. A kind that reaches here
     * without an answer is a kind whose certificate story was never decided,
     * and guessing is the one thing that must not happen.</p>
     */
    public String certRefFor(Domain domain) {
        if (domain.getKind().servedByPlatformProxy()) {
            return WILDCARD_CERT_REF_PREFIX + domain.getRootDomain();
        }
        if (domain.getKind() == DomainKind.CUSTOM) {
            return properties.letsEncryptCertRef();
        }
        throw new IllegalStateException(
                "no certRef is defined for domain kind " + domain.getKind());
    }

    private DomainVerificationView verification(Domain domain) {
        List<DomainVerificationView.RequiredRecord> records = List.of(
                new DomainVerificationView.RequiredRecord("A", domain.getFqdn(),
                        properties.proxyPublicIp()),
                new DomainVerificationView.RequiredRecord("TXT",
                        VERIFY_RECORD_PREFIX + domain.getFqdn(), domain.getVerificationToken()));
        return new DomainVerificationView(domain.getVerificationToken(), records,
                domain.isAVerified(), domain.isTxtVerified(),
                domain.getLastCheckedAt(), domain.getLastError());
    }
}
