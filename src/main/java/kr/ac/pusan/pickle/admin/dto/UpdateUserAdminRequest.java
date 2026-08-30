package kr.ac.pusan.pickle.admin.dto;

/**
 * Contract: PATCH /admin/users/{userId} body ({@code minProperties: 1}).
 * This surface changes only the global role. Organisation roles are edited one
 * row at a time through the dedicated grant and revoke endpoints.
 */
public record UpdateUserAdminRequest(AdminGlobalRole role) {

    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    public boolean isEmpty() {
        return role == null;
    }
}
