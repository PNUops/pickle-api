package kr.ac.pusan.pickle.publishing.dto;

import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code PublicationView} — a VM's HTTP publish state
 * (domain + route + certificate). v1: one per VM; null in VmDetail when
 * unpublished.
 */
public record PublicationView(
        String fqdn,
        DomainDetailView domain,
        @Nullable RouteView route,
        @Nullable CertificateView certificate) {
}
