package kr.ac.pusan.pickle.admin.dto;

import java.util.Collection;
import kr.ac.pusan.pickle.vm.Vm;

/** Contract schema {@code ResourceTotals} (sums over currently allocated VMs). */
public record ResourceTotalsResponse(int vcpu, long memoryMb, long diskGb) {

    public static ResourceTotalsResponse of(Collection<Vm> vms) {
        return new ResourceTotalsResponse(
                vms.stream().mapToInt(Vm::getVcpu).sum(),
                vms.stream().mapToLong(Vm::getMemoryMb).sum(),
                vms.stream().mapToLong(Vm::getDiskGb).sum());
    }
}
