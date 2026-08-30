package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.user.UserRole;

/** Roles managed by the global account-role surface. */
public enum AdminGlobalRole {
    USER,
    SYS_VIEWER,
    SYS_MANAGER,
    SYS_ADMIN;

    public UserRole toUserRole() {
        return UserRole.valueOf(name());
    }
}
