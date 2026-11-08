package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmDeleteKind;
import kr.ac.pusan.pickle.vm.VmDeletionService;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code VmDeletion} — the pending (or just-accepted)
 * deletion. {@code cancelable} reports the snapshot's state eligibility for
 * admin cancellation: SELF inside its grace window, ADMIN before destruction
 * starts, and FORCE never. The command rechecks state and authorization;
 * users cannot cancel.
 */
public record VmDeletionResponse(
        VmDeleteKind kind,
        Instant scheduledFor,
        Instant requestedAt,
        @Nullable UUID requestedById,
        @Nullable String reason,
        boolean cancelable) {

    /** Maps the {@code delete_*} columns; null when no deletion is pending. */
    public static VmDeletionResponse from(Vm vm, UUID requestedById) {
        if (vm.getDeleteKind() == null) {
            return null;
        }
        boolean cancelable = VmDeletionService.isCancelable(vm, Instant.now());
        return new VmDeletionResponse(vm.getDeleteKind(), vm.getDeleteScheduledFor(),
                vm.getDeleteRequestedAt(), requestedById, vm.getDeleteReason(),
                cancelable);
    }
}
