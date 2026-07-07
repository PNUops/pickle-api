package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.user.UserRole;

/**
 * Contract: PATCH /admin/users/{userId} body ({@code minProperties: 1}).
 * {@code role=ORG_ADMIN} requires {@code orgId}; other roles require it to be
 * null/absent — validated in the service.
 */
public record UpdateUserAdminRequest(UserRole role, Long orgId) {

    public boolean isEmpty() {
        return role == null && orgId == null;
    }
}
