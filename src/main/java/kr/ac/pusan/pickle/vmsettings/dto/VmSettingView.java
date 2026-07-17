package kr.ac.pusan.pickle.vmsettings.dto;

import java.time.Instant;
import java.util.List;
import kr.ac.pusan.pickle.group.GroupMemberRole;
import kr.ac.pusan.pickle.vmsettings.VmSettingValueType;
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
        List<String> allowedValues,
        JsonNode defaultValue,
        String label,
        String description,
        GroupMemberRole requiredRole,
        boolean editable,
        String updatedByName,
        Instant updatedAt) {
}
