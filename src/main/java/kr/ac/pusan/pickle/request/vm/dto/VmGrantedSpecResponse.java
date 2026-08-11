package kr.ac.pusan.pickle.request.vm.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;
import java.util.UUID;

/**
 * Contract schema {@code VmGrantedSpec}: what the reviewer granted for a VM.
 * Each reference carries its name for the same reason the requested spec does —
 * the grant outlives the catalog entry it points at.
 */
public record VmGrantedSpecResponse(
        Integer grantedVcpu,
        Integer grantedMemoryMb,
        Integer grantedDiskGb,
        UUID grantedImageId,
        @Schema(description = "승인된 OS 이미지의 표시 이름. 카탈로그에서 내려간 이미지도 이름이 남습니다.")
        String grantedImageName,
        @Nullable UUID nodeId,
        @Nullable
        @Schema(description = "배치된 노드의 이름. nodeId가 있을 때 함께 있습니다.")
        String nodeName) {
}
