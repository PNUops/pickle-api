package kr.ac.pusan.pickle.gpu;

import kr.ac.pusan.pickle.vm.Vm;

/** Read-only capability check; this interface never changes a VM's placement. */
public interface GpuMigrationReadiness {
    String blockingReason(Vm vm, long destinationNodeId);
}
