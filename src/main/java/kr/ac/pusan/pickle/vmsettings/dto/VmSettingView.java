package kr.ac.pusan.pickle.vmsettings.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.vmsettings.VmSettingValueType;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Contract schema {@code VmSettingView} — one VM setting with its registry
 * metadata and the requester-specific {@code editable} flag (server computes
 * the role/state comparison so the console need not).
 */
public record VmSettingView(
        String key,
        JsonNode value,
        VmSettingValueType valueType,
        @Nullable List<String> allowedValues,
        JsonNode defaultValue,
        String label,
        String description,
        ResourceRole requiredRole,
        boolean editable,
        @Nullable String updatedByName,
        @Nullable Instant updatedAt) {
}
