package kr.ac.pusan.pickle.security.institution.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;

/** Explicit desired authority; role names never create a grant. */
public record InstitutionPermissionInput(
        @NotNull InstitutionPermissionMode mode,
        @NotNull @Size(max = 200) List<@NotNull String> work,
        @NotNull Map<@NotNull InstitutionWorkGroup, @NotNull @Valid InstitutionWorkspaceScopeInput> scopes,
        @Schema(description = "기존 부여의 현재 버전. 새 부여는 0이며 회수 후 재부여한 버전은 재사용하지 않습니다.")
        @NotNull @Min(0) Long expectedRevision) {

    public InstitutionAssignment toAssignment(UUID accountId, UUID institutionId) {
        try {
            if (mode == null || work == null || scopes == null || expectedRevision == null || expectedRevision < 0) {
                throw new IllegalArgumentException("Incomplete desired assignment");
            }
            Set<InstitutionWork> selected = new HashSet<>();
            for (String id : work) {
                if (!selected.add(InstitutionWork.fromId(id).orElseThrow(() -> new IllegalArgumentException("Unknown work")))) {
                    throw new IllegalArgumentException("Duplicate work");
                }
            }
            Map<InstitutionWorkGroup, InstitutionWorkspaceScope> selectedScopes = new EnumMap<>(InstitutionWorkGroup.class);
            scopes.forEach((group, scope) -> selectedScopes.put(group, scope.toScope()));
            return new InstitutionAssignment(accountId, institutionId, InstitutionAssignment.Mode.valueOf(mode.name()),
                    selected, selectedScopes, expectedRevision);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw ApiException.validationFailed(List.of(new FieldValidationError("permission",
                    "부여 방식과 업무, 그룹별 워크스페이스 범위를 확인해 주세요. FULL은 업무·범위를 비우고, SELECTED는 업무를 하나 이상 지정해야 합니다.")));
        }
    }
}
