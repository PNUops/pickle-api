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
 * the Origin CA wildcard of the subdomain's own root for platform subdomains.
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
                domain.getCreatedAt());
    }

    public DomainDetailView toDomainDetail(Domain domain, UUID vmId) {
        DomainVerificationView verification = domain.getKind() == DomainKind.CUSTOM
                ? verification(domain) : null;
        return new DomainDetailView(domain.getPublicId(), vmId, domain.getKind(),
                domain.getFqdn(), domain.getRootDomain(), domain.getStatus(),
                domain.getVerifiedAt(), domain.getReleasedAt(), reservedUntil(domain),
                domain.getCreatedAt(), verification);
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

    /** The certificate backing a domain: its root's wildcard (platform) or LE (custom). */
    public Optional<Certificate> certificateFor(Domain domain) {
        if (domain.getKind() == DomainKind.CUSTOM) {
            return certificateRepository.findFirstByDomainIdAndStatusNot(domain.getId(),
                    CertificateStatus.REVOKED);
        }
        return certificateRepository.findLiveWildcard(CertificateKind.ORIGIN_CA_WILDCARD,
                wildcardScope(domain.getRootDomain()));
    }

    /**
     * The certRef the proxy-agent resolves to certificate material for this domain:
     * a per-domain LE cert for custom domains, else the wildcard of the domain's own
     * root. Deriving the platform ref from the root — rather than from a single
     * configured constant — is what lets a second root domain be introduced with no
     * code change: the agent gains a certificate, this gains nothing. An unknown
     * root reaches the agent as an unresolvable ref and is refused there, so a
     * misconfigured root cannot be rendered with some other root's certificate.
     */
    public String certRefFor(Domain domain) {
        return domain.getKind() == DomainKind.CUSTOM
                ? properties.letsEncryptCertRef()
                : WILDCARD_CERT_REF_PREFIX + domain.getRootDomain();
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
