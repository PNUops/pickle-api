package kr.ac.pusan.pickle.group.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.group.GroupKind;

/** Contract schema {@code CreateGroupRequest} (kind TEAM/PROJECT only — enforced in the service). */
public record CreateGroupRequest(
        @NotNull(message = "kind는 TEAM 또는 PROJECT여야 합니다.")
        GroupKind kind,

        @NotBlank(message = "그룹 이름을 입력해 주세요.")
        @Size(max = 100, message = "그룹 이름은 100자 이하여야 합니다.")
        String name,

        @NotBlank(message = "slug를 입력해 주세요.")
        @Pattern(regexp = "^[a-z0-9]([a-z0-9-]{0,38}[a-z0-9])?$",
                message = "slug는 소문자·숫자·하이픈만 사용할 수 있습니다 (하이픈으로 시작/끝 불가, 최대 40자).")
        String slug,

        @Size(max = 500, message = "설명은 500자 이하여야 합니다.")
        String description) {
}
