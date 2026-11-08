package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Bulk change kind {@code DOMAIN_VERIFY}. Carries nothing, as the single
 * re-verification has no body; the member exists so the kind is named in the
 * same shape as every other.
 */
@Schema(types = {"object"}, description = "빈 객체예요. 커스텀 도메인마다 소유권 재검증 요청을 접수해요.")
public record AdminBulkDomainVerifyChange() {
}
