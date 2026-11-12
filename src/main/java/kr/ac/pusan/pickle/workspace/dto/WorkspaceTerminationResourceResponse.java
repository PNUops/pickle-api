package kr.ac.pusan.pickle.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import org.jspecify.annotations.Nullable;

/** A registered resource that still prevents workspace deletion. */
public record WorkspaceTerminationResourceResponse(ResourceType type, UUID id,
        @Schema(description = "현재 조회 권한에 맞춘 이름이에요. VM 접근 권한이 없으면 SSH 슬러그 대신 표시 이름이나 공개 ID를 반환해요.")
        String name,
        @Schema(description = "현재 조회 권한으로 볼 수 있는 실제 기관이에요. 제한된 행이거나 연결된 기관이 없으면 null이에요.")
        @Nullable UUID orgId, @Nullable String orgName, String status,
        @Schema(description = "현재 호출 경로에서 해당 종류의 상세를 읽을 수 있는지 나타내요. 워크스페이스 OWNER라는 이유만으로 리소스 상세 권한을 얻지는 않아요.")
        boolean resourceReadable) { }
