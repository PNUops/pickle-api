package kr.ac.pusan.pickle.request.vm.dto;

import kr.ac.pusan.pickle.request.vm.VmRequestDetail;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code VmRequestSpec}: what a VM request asked for, reported
 * under {@code vm} in the request detail. {@code granted} is null until the
 * request is approved.
 */
public record VmRequestSpecResponse(
        UUID imageId,
        @Nullable UUID flavorId,
        int reqVcpu,
        int reqMemoryMb,
        int reqDiskGb,
        @Nullable String specReason,
        @Nullable String desiredSlug,
        @Nullable String desiredSubdomain,
        @Nullable String rootDomain,
        @Nullable VmGrantedSpecResponse granted) {

    public static VmRequestSpecResponse from(VmRequestDetail detail, UUID imageId, UUID flavorId,
            UUID grantedImageId, UUID nodeId) {
        VmGrantedSpecResponse granted = detail.getGrantedVcpu() != null
                ? new VmGrantedSpecResponse(detail.getGrantedVcpu(), detail.getGrantedMemoryMb(),
                        detail.getGrantedDiskGb(), grantedImageId, nodeId)
                : null;
        return new VmRequestSpecResponse(imageId, flavorId,
                detail.getReqVcpu(), detail.getReqMemoryMb(), detail.getReqDiskGb(),
                detail.getSpecReason(), detail.getDesiredSlug(), detail.getDesiredSubdomain(),
                detail.getRootDomain(), granted);
    }
}
