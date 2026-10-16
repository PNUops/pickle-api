package kr.ac.pusan.pickle.access.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import kr.ac.pusan.pickle.access.AccessGranteeType;
import kr.ac.pusan.pickle.access.ResourceRole;
import org.jspecify.annotations.Nullable;

/** Pure preview of one existing access-list mutation. */
public record ResourceAccessChangePreviewRequest(@NotNull Action action, @Nullable UUID grantId,
        @Nullable AccessGranteeType granteeType, @Nullable UUID userId, @Nullable ResourceRole role) {
    @io.swagger.v3.oas.annotations.media.Schema(name = "ResourceAccessChangeAction")
    public enum Action { ADD, UPDATE, REMOVE }
}
