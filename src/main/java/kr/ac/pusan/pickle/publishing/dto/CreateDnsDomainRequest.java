package kr.ac.pusan.pickle.publishing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code CreateDnsDomainRequest}.
 *
 * <p>The label only, not the whole name: the root is chosen from the ones this
 * platform issues under rather than typed, so a request cannot name a zone the
 * platform does not operate. Omitting the root asks for the default, which is
 * the single configured one while there is only one.</p>
 *
 * <p><b>No organisation field, on purpose.</b> The root carries it, so picking
 * where the name lives settles whose it is. Asking instead would make somebody
 * issuing a name for their own site answer an administrative question about
 * the platform, and nothing they could answer would be more true than what the
 * root already says.</p>
 */
public record CreateDnsDomainRequest(
        @Schema(description = "발급받을 이름. 루트 도메인 바로 아래 한 라벨입니다.", example = "myblog")
        @NotBlank
        String label,

        @Schema(description = "이 이름을 둘 루트 도메인. 비우면 기본 루트를 씁니다.",
                example = "pusan.dev")
        @Nullable
        String rootDomain,

        @Schema(description = "이 이름을 소유할 워크스페이스.")
        UUID workspaceId) {
}
