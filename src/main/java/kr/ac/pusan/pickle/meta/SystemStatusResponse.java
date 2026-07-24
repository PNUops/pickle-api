package kr.ac.pusan.pickle.meta;

/**
 * Contract: GET /meta/status response body. Public system status — the
 * maintenance flag and its notice, the global banner, and the operator contact
 * email. Nullable string fields are {@code null} when unset (blank collapses to
 * null in {@link kr.ac.pusan.pickle.settings.SettingsService#string}).
 */
public record SystemStatusResponse(
        boolean maintenance,
        String maintenanceMessage,
        String bannerMessage,
        String contactEmail) {
}
