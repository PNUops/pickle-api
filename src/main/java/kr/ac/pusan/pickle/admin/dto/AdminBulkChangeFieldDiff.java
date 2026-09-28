package kr.ac.pusan.pickle.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;

/** One field a bulk change moves on one target: the value it has and the value it would get. */
public record AdminBulkChangeFieldDiff(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "필드 이름")
        String field,
        @Schema(description = "현재 값. 문자열이나 숫자, 배열, null입니다.")
        JsonNode oldValue,
        @Schema(description = "바뀔 값. 문자열이나 숫자, 배열, null입니다.")
        JsonNode newValue) {
}
