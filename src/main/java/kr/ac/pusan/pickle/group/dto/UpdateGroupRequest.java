package kr.ac.pusan.pickle.group.dto;

import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Contract: PATCH /groups/{groupId} body ({@code minProperties: 1}).
 * Field presence is tracked via the setters so an explicit
 * {@code "description": null} clears the description, while an absent field
 * leaves it untouched.
 */
public class UpdateGroupRequest {

    @Size(max = 100, message = "그룹 이름은 100자 이하여야 합니다.")
    private String name;
    private boolean nameSet;

    @Size(max = 500, message = "설명은 500자 이하여야 합니다.")
    private @Nullable String description;
    private boolean descriptionSet;

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

    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    public boolean isEmpty() {
        return !nameSet && !descriptionSet;
    }
}
