package kr.ac.pusan.pickle.publishing.dto;

import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code PublishRequest}. Only the exposed port and an optional
 * custom domain — the platform subdomain name is fixed at approval, never here,
 * and there is deliberately no target-IP field (SSRF guard: the server forces the
 * VM's own allocated IP). Port/domain rules are validated server-side (422).
 */
public record PublishRequest(Integer port, @Nullable String customDomain) {
}
