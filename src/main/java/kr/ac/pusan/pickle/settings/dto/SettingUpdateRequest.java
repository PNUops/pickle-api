package kr.ac.pusan.pickle.settings.dto;

import tools.jackson.databind.JsonNode;

/**
 * Contract {@code SettingUpdateRequest}: the full replacement value as raw
 * JSON — type/range validation happens per key in the service.
 */
public record SettingUpdateRequest(
        @jakarta.validation.constraints.NotNull(message = "설정 값을 입력해 주세요.")
        JsonNode value) {
}
