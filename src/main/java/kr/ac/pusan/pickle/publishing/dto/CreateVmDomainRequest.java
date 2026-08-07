package kr.ac.pusan.pickle.publishing.dto;

import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code CreateVmDomainRequest}. The exposed port (default 80),
 * the platform subdomain label (omitted ⇒ 422 unless {@code customDomain} is
 * given — there is no auto-generated fallback), an optional root domain, and an
 * optional custom domain (mutually exclusive with {@code subdomain}). There is
 * deliberately no target-IP field (SSRF guard: the server forces the VM's own
 * allocated IP). All name rules are validated server-side (422).
 */
public record CreateVmDomainRequest(Integer port, @Nullable String subdomain,
        @Nullable String rootDomain, @Nullable String customDomain) {
}
