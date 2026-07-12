package kr.ac.pusan.pickle.publishing.dto;

/**
 * Contract schema {@code PublicationView} — a VM's HTTP publish state
 * (domain + route + certificate). v1: one per VM; null in VmDetail when
 * unpublished.
 */
public record PublicationView(
        String fqdn,
        DomainDetailView domain,
        RouteView route,
        CertificateView certificate) {
}
