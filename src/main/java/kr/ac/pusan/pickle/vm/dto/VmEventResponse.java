package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventType;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code VmEvent} ({@code actorId} null = system/automatic). */
public record VmEventResponse(
        UUID id,
        VmEventType type,
        @Nullable UUID actorId,
        @Nullable String detail,
        Instant createdAt) {

    public static VmEventResponse from(VmEvent event, UUID actorId) {
        return new VmEventResponse(event.getPublicId(), event.getType(), actorId,
                event.getDetail(), event.getCreatedAt());
    }
}
