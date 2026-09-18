package kr.ac.pusan.pickle.workspace.dto;

import jakarta.validation.constraints.Size;
import kr.ac.pusan.pickle.workspace.CreatableWorkspaceKind;
import org.jspecify.annotations.Nullable;

/**
 * Contract: PATCH /workspaces/{workspaceId} body ({@code minProperties: 1}).
 * Field presence is tracked via the setters so an explicit
 * {@code "description": null} clears the description, while an absent field
 * leaves it untouched.
 */
public class UpdateWorkspaceRequest {

    @Size(max = 100, message = "워크스페이스 이름은 100자 이하여야 합니다.")
    private String name;
    private boolean nameSet;

    @Size(max = 500, message = "설명은 500자 이하여야 합니다.")
    private @Nullable String description;
    private boolean descriptionSet;

    // Deliberately not @Nullable: null is not a value this field can carry.
    // "leave the kind alone" is the field being absent, which kindSet tracks,
    // and there is no state a workspace can be in with no kind. Marking it
    // nullable published a schema that allowed a value the server always
    // refuses with 422.
    private CreatableWorkspaceKind kind;
    private boolean kindSet;

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

    @io.swagger.v3.oas.annotations.media.Schema(
            description = "워크스페이스 유형. PERSONAL 워크스페이스는 유형을 바꿀 수 없습니다")
    public CreatableWorkspaceKind getKind() {
        return kind;
    }

    public void setKind(CreatableWorkspaceKind kind) {
        this.kind = kind;
        this.kindSet = true;
    }

    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    public boolean isKindSet() {
        return kindSet;
    }

    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    public boolean isEmpty() {
        return !nameSet && !descriptionSet && !kindSet;
    }
}
