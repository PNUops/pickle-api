package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** A logical catalog identity, separate from a physical replica's status. */
public record AdminApprovalImageReference(UUID id, String name, int version,
        @Schema(description = "동일 revision의 호환 ACTIVE replica가 ACTIVE 노드에 있는지. "
                + "이 값은 개별 VM 용량이나 실제 생성 완료를 보장하지 않습니다.")
        boolean selectableNow) { }
