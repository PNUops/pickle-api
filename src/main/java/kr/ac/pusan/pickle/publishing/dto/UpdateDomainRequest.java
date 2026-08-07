package kr.ac.pusan.pickle.publishing.dto;

/**
 * Contract schema {@code UpdateDomainRequest} — the port-only edit for
 * {@code PATCH /domains/{domainId}}. Changing the name means creating a new
 * domain (and releasing this one); the port is the only in-place mutable field.
 */
public record UpdateDomainRequest(Integer port) {
}
