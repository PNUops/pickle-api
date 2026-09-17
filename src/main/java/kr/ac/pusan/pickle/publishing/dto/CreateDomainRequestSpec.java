package kr.ac.pusan.pickle.publishing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/**
 * Contract schema {@code CreateDomainRequestSpec}: the two things a domain
 * request asks for.
 *
 * <p>No organisation, on purpose. Every other kind takes the organisation from
 * the common part of the request, but a name takes its own from the root it is
 * issued under, and asking the applicant would give one name two answers about
 * whose it is. The service copies the root's organisation over whatever the
 * common part carries, and the screen does not show that field for this
 * kind.</p>
 *
 * <p>No period either. A name's life is governed by its renewal deadline, and a
 * granted period beside it would be a second clock — the two would disagree and
 * a screen would have to explain which one wins.</p>
 */
public record CreateDomainRequestSpec(
        @NotBlank(message = "이름을 입력해 주세요.")
        @Schema(description = "루트 도메인 바로 아래 한 라벨. 영문 소문자와 숫자, 하이픈만 쓸 수 있습니다.",
                example = "myblog")
        String label,

        @NotBlank(message = "루트 도메인을 골라 주세요.")
        @Schema(description = "이름을 발급받을 루트 도메인. 이 이름이 속할 기관이 여기서 정해집니다.",
                example = "pusan.dev")
        String rootDomain) {
}
