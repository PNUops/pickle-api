package kr.ac.pusan.pickle.request.vm.dto;

import org.jspecify.annotations.Nullable;
import java.util.UUID;

/** Contract schema {@code VmGrantedSpec}: what the reviewer granted for a VM. */
public record VmGrantedSpecResponse(
        Integer grantedVcpu,
        Integer grantedMemoryMb,
        Integer grantedDiskGb,
        UUID grantedImageId,
        @Nullable UUID nodeId) {
}
