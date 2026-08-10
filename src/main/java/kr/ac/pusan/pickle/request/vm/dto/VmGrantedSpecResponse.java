package kr.ac.pusan.pickle.request.vm.dto;

import org.jspecify.annotations.Nullable;

/** Contract schema {@code VmGrantedSpec}: what the reviewer granted for a VM. */
public record VmGrantedSpecResponse(
        Integer grantedVcpu,
        Integer grantedMemoryMb,
        Integer grantedDiskGb,
        Long grantedImageId,
        @Nullable Long nodeId) {
}
