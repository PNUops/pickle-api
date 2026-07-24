package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.publishing.Route;
import kr.ac.pusan.pickle.publishing.RouteStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code RouteView} — a VM's publish route status. */
public record RouteView(
        int targetPort,
        String protocol,
        RouteStatus status,
        @Nullable Instant appliedAt,
        @Nullable String lastError) {

    public static RouteView from(Route route) {
        return new RouteView(route.getTargetPort(), route.getProtocol(), route.getStatus(),
                route.getAppliedAt(), route.getLastError());
    }
}
