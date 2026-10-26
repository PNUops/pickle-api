package kr.ac.pusan.pickle.observation;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** No source registration or secret is supplied by a profile or migration. */
@ConfigurationProperties("pickle.inference-observations")
public record ObservationProperties(boolean enabled, @Nullable String token,
        @Nullable String previousToken, @Nullable String allowedSourceIp,
        @Nullable UUID sourceId, @Nullable UUID upstreamId) {
    public boolean configured() {
        return enabled && token != null && !token.isBlank() && allowedSourceIp != null
                && !allowedSourceIp.isBlank() && bound();
    }
    public boolean bound() { return sourceId != null && upstreamId != null; }
    @Override public String toString() {
        return "ObservationProperties[enabled=" + enabled + ", secrets omitted]";
    }
}
