package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventType;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code VmEvent} ({@code actorId} null = system/automatic). */
public record VmEventResponse(
        Long id,
        VmEventType type,
        @Nullable Long actorId,
        @Nullable String detail,
        Instant createdAt) {

    public static VmEventResponse from(VmEvent event) {
        return new VmEventResponse(event.getId(), event.getType(), event.getActorId(),
                event.getDetail(), event.getCreatedAt());
    }
}
