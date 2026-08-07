package kr.ac.pusan.pickle.publishing.dto;

import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code PublicationView} — one published domain of a VM
 * (domain + route + certificate). A VM may carry several (contract v0.29.0);
 * {@code VmDetail.publications} lists them in id order.
 */
public record PublicationView(
        String fqdn,
        DomainDetailView domain,
        @Nullable RouteView route,
        @Nullable CertificateView certificate) {
}
