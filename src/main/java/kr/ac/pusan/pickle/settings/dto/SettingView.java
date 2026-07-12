package kr.ac.pusan.pickle.settings.dto;

import java.time.Instant;
import kr.ac.pusan.pickle.settings.SettingValueType;
import tools.jackson.databind.JsonNode;

/** Contract {@code SettingView}: one operator-tunable settings row. */
public record SettingView(
        String key,
        JsonNode value,
        SettingValueType valueType,
        String description,
        boolean editable,
        Instant updatedAt) {
}
