package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.common.validation.Slugs;
import org.jspecify.annotations.Nullable;

/** Contract: POST /admin/orgs body. */
public record CreateOrgRequest(
        @NotBlank(message = "기관 이름을 입력해 주세요.")
        @Size(max = 100, message = "기관 이름은 100자 이하여야 합니다.")
        String name,

        @NotBlank(message = "slug를 입력해 주세요.")
        @Pattern(regexp = Slugs.PATTERN, message = Slugs.MESSAGE)
        String slug,

        @Size(max = 500, message = "설명은 500자 이하여야 합니다.")
        @Nullable String description) {
}
