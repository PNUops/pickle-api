package kr.ac.pusan.pickle.gpu;

import kr.ac.pusan.pickle.vm.Vm;

/** A null reason means the guest was positively verified safe for GPU attachment. */
public interface GpuGuestReadiness {
    String blockingReason(Vm vm);
}
