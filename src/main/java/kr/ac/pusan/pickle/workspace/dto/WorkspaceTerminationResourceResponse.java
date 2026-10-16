package kr.ac.pusan.pickle.workspace.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import org.jspecify.annotations.Nullable;

/** A registered resource that still prevents workspace deletion. */
public record WorkspaceTerminationResourceResponse(ResourceType type, UUID id,
        @Schema(description = "현재 조회 권한에 맞춘 이름입니다. VM 접근 권한이 없으면 SSH 슬러그 대신 표시 이름 또는 공개 ID가 들어갑니다.")
        String name,
        @Schema(description = "현재 조회 권한으로 공개되는 실제 기관입니다. 제한된 행이나 연결 기관이 없는 경우 null입니다.")
        @Nullable UUID orgId, @Nullable String orgName, String status,
        @Schema(description = "현재 호출 경로에서 기존 종류별 단건 상세를 읽을 수 있는지입니다. 워크스페이스 OWNER 자체는 자원 상세 권한을 뜻하지 않습니다.")
        boolean resourceReadable) { }
