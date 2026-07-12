package kr.ac.pusan.pickle.publishing;

import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.config.PublishingProperties;
import kr.ac.pusan.pickle.publishing.dto.CertificateView;
import kr.ac.pusan.pickle.publishing.dto.DomainDetailView;
import kr.ac.pusan.pickle.publishing.dto.DomainVerificationView;
import kr.ac.pusan.pickle.publishing.dto.PublicationView;
import kr.ac.pusan.pickle.publishing.dto.RouteView;
import org.springframework.stereotype.Component;

/**
 * Assembles the contract publish views (DomainDetail, PublicationView) from the
 * domain/route/certificate rows. Custom domains carry a verification block (the
 * A + TXT records to create and the current polling state); platform subdomains
 * do not (null). The certificate is the per-domain LE cert for custom domains and
 * the shared Origin CA wildcard for platform subdomains.
 */
@Component
public class PublicationAssembler {

    /** TXT ownership record prefix (docs/plan/06: {@code _pickle-verify.<fqdn>}). */
    public static final String VERIFY_RECORD_PREFIX = "_pickle-verify.";

    private final RouteRepository routeRepository;
    private final CertificateRepository certificateRepository;
    private final PublishingProperties properties;

    public PublicationAssembler(RouteRepository routeRepository,
            CertificateRepository certificateRepository, PublishingProperties properties) {
        this.routeRepository = routeRepository;
        this.certificateRepository = certificateRepository;
        this.properties = properties;
    }

    public DomainDetailView toDomainDetail(Domain domain) {
        DomainVerificationView verification = domain.getKind() == DomainKind.CUSTOM
                ? verification(domain) : null;
        return new DomainDetailView(domain.getId(), domain.getVmId(), domain.getKind(),
                domain.getFqdn(), domain.getRootDomain(), domain.getStatus(),
                domain.getVerifiedAt(), domain.getCreatedAt(), verification);
    }

    /** The full publish view for a domain — its live route and certificate. */
    public PublicationView toPublication(Domain domain) {
        RouteView route = routeRepository.findFirstByDomainIdAndStatusNot(domain.getId(), RouteStatus.REMOVED)
                .map(RouteView::from)
                .orElse(null);
        CertificateView certificate = certificateFor(domain).map(CertificateView::from).orElse(null);
        return new PublicationView(domain.getFqdn(), toDomainDetail(domain), route, certificate);
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

    /** The certificate backing a domain: shared wildcard (platform) or LE (custom). */
    public Optional<Certificate> certificateFor(Domain domain) {
        if (domain.getKind() == DomainKind.CUSTOM) {
            return certificateRepository.findFirstByDomainIdAndStatusNot(domain.getId(),
                    CertificateStatus.REVOKED);
        }
        return certificateRepository.findFirstByKindAndScope(CertificateKind.ORIGIN_CA_WILDCARD,
                "*." + domain.getRootDomain());
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
