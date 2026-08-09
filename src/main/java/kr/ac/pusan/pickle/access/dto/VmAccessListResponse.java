package kr.ac.pusan.pickle.access.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.jspecify.annotations.Nullable;

/**
 * A VM's access list, with just enough of the VM to say what the list belongs to.
 *
 * <p>The identity is here because the people most likely to open this are the
 * ones who cannot open the VM itself: an owner of the group holds no grant, so
 * the detail view is closed to them, and a screen that showed only rows of names
 * and rungs would never say which machine they were deciding about. What it
 * carries is exactly what such a person may already see in the list — name,
 * state, owning group — and nothing from inside.
 */
@Schema(description = "VM 접근 권한 목록과, 그 목록이 어느 VM의 것인지 알려 주는 최소 정보")
public record VmAccessListResponse(
        @Schema(description = "이 목록이 속한 VM") Vm vm,
        @Schema(description = "접근 권한 항목") List<VmAccessGrantView> grants) {

    /** Name, state and owning group — the same fields a restricted row shows. */
    @Schema(description = "접근 권한이 없는 사람에게도 보이는 범위의 VM 정보")
    public record Vm(
            Long id,
            String name,
            @Schema(description = "표시명. 지정되지 않았으면 null입니다.")
            @Nullable String displayName,
            VmStatus status,
            Long groupId,
            String groupName) {
    }

    public static VmAccessListResponse of(kr.ac.pusan.pickle.vm.Vm vm, String groupName,
            @Nullable String displayName, List<VmAccessGrantView> grants) {
        return new VmAccessListResponse(
                new Vm(vm.getId(), vm.getName(), displayName, vm.getStatus(), vm.getGroupId(),
                        groupName),
                grants);
    }
}
