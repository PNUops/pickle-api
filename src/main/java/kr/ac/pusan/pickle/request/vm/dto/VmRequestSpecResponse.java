package kr.ac.pusan.pickle.request.vm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import kr.ac.pusan.pickle.inventory.Node;
import kr.ac.pusan.pickle.inventory.OsImage;
import kr.ac.pusan.pickle.inventory.VmFlavor;
import kr.ac.pusan.pickle.request.vm.VmRequestDetail;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code VmRequestSpec}: what a VM request asked for, reported
 * under {@code vm} in the request detail. {@code granted} is null until the
 * request is approved.
 *
 * <p>Every catalog reference carries its name beside its id. The request is a
 * historical record and the catalog is not: an image retired since the request
 * was filed is no longer in the catalog the client fetched, so a client that
 * resolves the name for itself has nothing to resolve it against and the
 * reference renders as unknown. The name travels with the reference for that
 * reason.</p>
 *
 * <p>Each name is present exactly when its id is — both come from the same row,
 * and catalog rows are retired by status, never deleted.</p>
 */
public record VmRequestSpecResponse(
        UUID imageId,
        @Schema(description = "요청한 OS 이미지의 표시 이름. 카탈로그에서 내려간 이미지도 이름이 남습니다.")
        String imageName,
        @Nullable UUID flavorId,
        @Nullable
        @Schema(description = "요청한 사양 프리셋의 표시 이름. flavorId가 있을 때 함께 있습니다.")
        String flavorName,
        int reqVcpu,
        int reqMemoryMb,
        int reqDiskGb,
        @Nullable String specReason,
        @Nullable String desiredSlug,
        @Nullable VmGrantedSpecResponse granted) {

    public static VmRequestSpecResponse from(VmRequestDetail detail, OsImage image,
            @Nullable VmFlavor flavor, OsImage grantedImage, @Nullable Node node) {
        VmGrantedSpecResponse granted = detail.getGrantedVcpu() != null
                ? new VmGrantedSpecResponse(detail.getGrantedVcpu(), detail.getGrantedMemoryMb(),
                        detail.getGrantedDiskGb(), grantedImage.getPublicId(),
                        grantedImage.getDisplayName(),
                        node == null ? null : node.getPublicId(),
                        node == null ? null : node.getName())
                : null;
        return new VmRequestSpecResponse(image.getPublicId(), image.getDisplayName(),
                flavor == null ? null : flavor.getPublicId(),
                flavor == null ? null : flavor.getDisplayName(),
                detail.getReqVcpu(), detail.getReqMemoryMb(), detail.getReqDiskGb(),
                detail.getSpecReason(), detail.getDesiredSlug(), granted);
    }
}
