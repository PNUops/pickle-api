package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.orgs.OrgStatus;
import org.jspecify.annotations.Nullable;

/**
 * Contract: PATCH /admin/orgs/{orgId} body ({@code minProperties: 1}).
 * Presence-tracked so an explicit {@code "description": null} clears it.
 */
public class UpdateOrgRequest {

    @Size(max = 100, message = "기관 이름은 100자 이하여야 합니다.")
    private String name;
    private boolean nameSet;

    @Size(max = 500, message = "설명은 500자 이하여야 합니다.")
    private @Nullable String description;
    private boolean descriptionSet;

    private OrgStatus status;
    private boolean statusSet;

    private Boolean hidden;
    private boolean hiddenSet;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.nameSet = true;
    }

    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    public boolean isNameSet() {
        return nameSet;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
        this.descriptionSet = true;
    }

    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    public boolean isDescriptionSet() {
        return descriptionSet;
    }

    public OrgStatus getStatus() {
        return status;
    }

    public void setStatus(OrgStatus status) {
        this.status = status;
        this.statusSet = true;
    }

    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    public boolean isStatusSet() {
        return statusSet;
    }

    public Boolean getHidden() {
        return hidden;
    }

    public void setHidden(Boolean hidden) {
        this.hidden = hidden;
        this.hiddenSet = true;
    }

    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    public boolean isHiddenSet() {
        return hiddenSet;
    }

    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    public boolean isEmpty() {
        return !nameSet && !descriptionSet && !statusSet && !hiddenSet;
    }
}
