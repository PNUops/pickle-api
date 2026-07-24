package kr.ac.pusan.pickle.admin.dto;

import java.time.LocalDate;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code VmBrief} (approval-context VM line). */
public record VmBriefResponse(
        Long id,
        String name,
        VmStatus status,
        int vcpu,
        int memoryMb,
        int diskGb,
        @Nullable LocalDate endDate) {

    public static VmBriefResponse from(Vm vm) {
        return new VmBriefResponse(vm.getId(), vm.getName(), vm.getStatus(),
                vm.getVcpu(), vm.getMemoryMb(), vm.getDiskGb(), vm.getEndDate());
    }
}
