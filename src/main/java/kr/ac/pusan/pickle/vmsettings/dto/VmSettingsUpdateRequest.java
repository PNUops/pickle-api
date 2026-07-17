package kr.ac.pusan.pickle.vmsettings.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Contract schema {@code VmSettingsUpdateRequest} — a partial map of setting
 * key → new value. Emptiness and per-key type/role validation happen in the
 * service (the values are typed per the registry, not the DTO).
 */
public record VmSettingsUpdateRequest(
        @NotNull Map<String, JsonNode> settings) {
}
