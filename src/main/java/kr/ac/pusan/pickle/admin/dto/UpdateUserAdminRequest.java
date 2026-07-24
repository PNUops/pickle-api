package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.user.UserRole;

/**
 * Contract: PATCH /admin/users/{userId} body ({@code minProperties: 1}).
 * An org-tier role ({@code ORG_ADMIN} or {@code ORG_MANAGER}) requires
 * {@code orgId}; other roles require it to be null/absent — validated in the
 * service.
 */
public record UpdateUserAdminRequest(UserRole role, Long orgId) {

    @io.swagger.v3.oas.annotations.media.Schema(hidden = true)
    public boolean isEmpty() {
        return role == null && orgId == null;
    }
}
