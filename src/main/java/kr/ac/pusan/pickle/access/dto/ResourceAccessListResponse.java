package kr.ac.pusan.pickle.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import kr.ac.pusan.pickle.access.ResourceType;
import org.jspecify.annotations.Nullable;

/**
 * A resource's access list, with just enough of the resource to say what the
 * list belongs to.
 *
 * <p>The identity is here because the people most likely to open this are the
 * ones who cannot open the resource itself: an owner of the workspace holds no
 * grant, so the detail view is closed to them, and a screen that showed only
 * rows of names and rungs would never say which thing they were deciding
 * about. What it carries is exactly what such a person may already see in the
 * list — name, state, owning workspace — and nothing from inside.
 *
 * <p>Written in resource terms rather than VM terms so a second kind of
 * resource opens its own access list with a controller and nothing else.
 */
@Schema(description = "리소스 접근 권한 목록과, 그 목록이 어느 리소스의 것인지 알려 주는 최소 정보")
public record ResourceAccessListResponse(
        @Schema(description = "이 목록이 속한 리소스") ResourceBrief resource,
        @Schema(description = "접근 권한 항목") List<ResourceAccessGrantView> grants) {

    /** Name, state and owning workspace — the same fields a restricted row shows. */
    @Schema(description = "접근 권한이 없는 사람에게도 보이는 범위의 리소스 정보")
    public record ResourceBrief(
            Long id,
            ResourceType type,
            String name,
            @Schema(description = "표시명. 지정되지 않았으면 null입니다.")
            @Nullable String displayName,
            @Schema(description = "리소스 종류의 상태값. 종류마다 어휘가 다릅니다.")
            String status,
            Long workspaceId,
            String workspaceName) {
    }

    public static ResourceAccessListResponse of(kr.ac.pusan.pickle.vm.Vm vm, String workspaceName,
            @Nullable String displayName, List<ResourceAccessGrantView> grants) {
        return new ResourceAccessListResponse(
                new ResourceBrief(vm.getId(), ResourceType.VM, vm.getName(), displayName,
                        vm.getStatus().name(), vm.getWorkspaceId(), workspaceName),
                grants);
    }
}
