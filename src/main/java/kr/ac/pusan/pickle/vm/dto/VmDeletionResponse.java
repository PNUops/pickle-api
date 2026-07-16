package kr.ac.pusan.pickle.vm.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmDeleteKind;
import kr.ac.pusan.pickle.vm.VmStatus;

/**
 * Contract schema {@code VmDeletion} — the pending (or just-accepted)
 * deletion. {@code cancelable} answers "can an <b>admin</b> cancel this right
 * now": false only for force deletes, already-destroyed VMs and elapsed
 * grace/notice times; users can never cancel (contract deletion policy).
 */
public record VmDeletionResponse(
        VmDeleteKind kind,
        Instant scheduledFor,
        Instant requestedAt,
        Long requestedById,
        String reason,
        boolean cancelable) {

    /** Maps the {@code delete_*} columns; null when no deletion is pending. */
    public static VmDeletionResponse from(Vm vm) {
        if (vm.getDeleteKind() == null) {
            return null;
        }
        boolean cancelable = vm.getDeleteKind() != VmDeleteKind.FORCE
                && vm.getStatus() != VmStatus.DELETED
                && vm.getDeleteScheduledFor() != null
                && vm.getDeleteScheduledFor().isAfter(Instant.now());
        return new VmDeletionResponse(vm.getDeleteKind(), vm.getDeleteScheduledFor(),
                vm.getDeleteRequestedAt(), vm.getDeleteRequestedBy(), vm.getDeleteReason(),
                cancelable);
    }
}
