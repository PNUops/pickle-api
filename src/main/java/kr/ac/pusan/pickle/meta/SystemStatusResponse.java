package kr.ac.pusan.pickle.meta;

import org.jspecify.annotations.Nullable;

/**
 * Contract: GET /meta/status response body. Public system status — the
 * maintenance flag and its notice, the global banner, and the operator contact
 * email. Nullable string fields are {@code null} when unset (blank collapses to
 * null in {@link kr.ac.pusan.pickle.settings.SettingsService#string}).
 */
public record SystemStatusResponse(
        boolean maintenance,
        @Nullable String maintenanceMessage,
        @Nullable String bannerMessage,
        @Nullable String contactEmail) {
}
