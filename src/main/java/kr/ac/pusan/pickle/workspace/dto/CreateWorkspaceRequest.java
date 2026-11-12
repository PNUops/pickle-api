package kr.ac.pusan.pickle.workspace.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.workspace.CreatableWorkspaceKind;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code CreateWorkspaceRequest}. */
public record CreateWorkspaceRequest(
        @NotNull(message = "워크스페이스 유형을 선택해 주세요.")
        @io.swagger.v3.oas.annotations.media.Schema(
                description = "워크스페이스 유형이에요. PERSONAL은 가입할 때 자동 생성되며 TEAM은 폐기된 값이라 "
                        + "요청으로 만들 수 없어요.")
        CreatableWorkspaceKind kind,

        @NotBlank(message = "워크스페이스 이름을 입력해 주세요.")
        @Size(max = 100, message = "워크스페이스 이름은 100자 이내로 입력해 주세요.")
        String name,

        @Size(max = 500, message = "설명은 500자 이내로 입력해 주세요.")
        @Nullable String description) {
}
