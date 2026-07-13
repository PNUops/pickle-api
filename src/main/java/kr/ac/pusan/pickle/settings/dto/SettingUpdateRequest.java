package kr.ac.pusan.pickle.settings.dto;

import tools.jackson.databind.JsonNode;

/**
 * Contract {@code SettingUpdateRequest}: the full replacement value as raw
 * JSON — type/range validation happens per key in the service.
 */
public record SettingUpdateRequest(JsonNode value) {
}
