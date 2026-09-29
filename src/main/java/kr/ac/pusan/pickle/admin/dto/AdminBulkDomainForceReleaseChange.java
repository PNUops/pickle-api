package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Bulk change kind {@code DOMAIN_FORCE_RELEASE}. Carries nothing, as the
 * single force release has no body; the member exists so the kind is named
 * in the same shape as every other.
 */
@Schema(types = {"object"}, description = "빈 객체. 강제 해제는 되돌릴 수 없고 이름을 즉시 회수합니다.")
public record AdminBulkDomainForceReleaseChange() {
}
