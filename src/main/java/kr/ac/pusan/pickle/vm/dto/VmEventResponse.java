package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.vm.VmActorKind;
import kr.ac.pusan.pickle.vm.VmEvent;
import kr.ac.pusan.pickle.vm.VmEventType;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code VmEvent}. {@code actorKind} says which surface acted;
 * {@code actorId} and {@code actorName} name the person behind it and are
 * <b>both null unless this audience may see who it was</b>: the member-facing
 * history reports an administrator's intervention as {@code ADMIN} and nothing
 * more, while the admin surface fills them in. The emptying happens here, in
 * the response, rather than in the client — a name the console hides is still
 * a name that was sent.
 */
public record VmEventResponse(
        UUID id,
        VmEventType type,
        VmActorKind actorKind,
        @Nullable UUID actorId,
        @Nullable String actorName,
        @Nullable String detail,
        Instant createdAt) {

    /**
     * @param actorId   the actor's public id, or null when the audience may not
     *                  see it
     * @param actorName the actor's display name, or null for the same reason
     *                  (also null for an account whose name is gone)
     */
    public static VmEventResponse from(VmEvent event, @Nullable UUID actorId,
            @Nullable String actorName) {
        return new VmEventResponse(event.getPublicId(), event.getType(), event.getActorKind(),
                actorId, actorName, event.getDetail(), event.getCreatedAt());
    }
}
