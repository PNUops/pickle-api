package kr.ac.pusan.pickle.publishing.dto;

import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code PublishRequest}. The exposed port, the platform
 * subdomain (v0.22.0 self-service — omitted ⇒ the request-form value; neither
 * present ⇒ 422, there is no auto-generated fallback), an optional root domain,
 * and an optional custom domain (mutually exclusive with {@code subdomain}).
 * There is deliberately no target-IP field (SSRF guard: the server forces the
 * VM's own allocated IP). All name rules are validated server-side (422).
 */
public record PublishRequest(Integer port, @Nullable String subdomain,
        @Nullable String rootDomain, @Nullable String customDomain) {
}
