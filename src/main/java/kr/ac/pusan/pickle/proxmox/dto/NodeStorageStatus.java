package kr.ac.pusan.pickle.proxmox.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of {@code GET /nodes/{n}/storage} (requires {@code Datastore.Audit};
 * the token's ACL scopes the answer to the storages the platform uses). For an
 * over-provisioned thin pool {@code total}/{@code used} are the pool's real
 * bytes, not the sum of guest disk sizes.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NodeStorageStatus(
        String storage,
        String type,
        Integer active,
        Integer enabled,
        Long total,
        Long used,
        Long avail,
        @JsonProperty("used_fraction") Double usedFraction) {

    public boolean isActive() {
        return active != null && active != 0;
    }
}
