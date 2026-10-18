package kr.ac.pusan.pickle.admin.dto;

import java.time.LocalDate;
import java.util.UUID;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code VmBrief} (approval-context VM line). */
public record VmBriefResponse(
        UUID id,
        String name,
        VmStatus status,
        @Nullable Integer vcpu,
        @Nullable Integer memoryMb,
        @Nullable Integer diskGb,
        @Nullable LocalDate endDate,
        @Nullable UUID orgId,
        boolean canReadDetail) {

    public static VmBriefResponse from(Vm vm, @Nullable UUID orgId, boolean canReadDetail) {
        return new VmBriefResponse(vm.getPublicId(), vm.getName(), vm.getStatus(),
                vm.getVcpu(), vm.getMemoryMb(), vm.getDiskGb(), vm.getEndDate(), orgId, canReadDetail);
    }
}
