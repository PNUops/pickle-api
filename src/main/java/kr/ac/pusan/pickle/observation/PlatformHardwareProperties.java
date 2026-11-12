package kr.ac.pusan.pickle.observation;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dedicated reporter identity and roster; hardware values are never configured. */
@ConfigurationProperties("pickle.platform-hardware-observations")
public record PlatformHardwareProperties(boolean enabled, @Nullable String token,
        @Nullable String previousToken, @Nullable String allowedSourceIp,
        @Nullable UUID sourceId, @Nullable List<String> expectedAssetIds,
        @Nullable Duration maxAge) {
    public boolean configured() {
        return enabled && token != null && !token.isBlank() && sourceId != null
                && allowedSourceIp != null && !allowedSourceIp.isBlank()
                && expectedAssetIds != null && !expectedAssetIds.isEmpty() && expectedAssetIds.size() <= 32
                && expectedAssetIds.stream().allMatch(PlatformHardwareValidator::validId)
                && new HashSet<>(expectedAssetIds).size() == expectedAssetIds.size()
                && !freshness().isZero() && !freshness().isNegative();
    }
    public Duration freshness() { return maxAge == null ? Duration.ofMinutes(15) : maxAge; }
    @Override public String toString() {
        return "PlatformHardwareProperties[enabled=" + enabled + ", credentials and roster omitted]";
    }
}
