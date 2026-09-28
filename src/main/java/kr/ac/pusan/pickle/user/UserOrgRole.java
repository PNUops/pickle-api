package kr.ac.pusan.pickle.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One organisation an account holds a role in, and the role there (V90).
 *
 * <p>Replaces the single {@code users.org_id}. An account may hold several of
 * these, and may hold a different role in each; {@code users.role} is the
 * highest role across them, so the {@code @PreAuthorize} gates keep asking
 * whether the account may ever do a thing while the service layer asks whether
 * it may in the organisation actually being touched.
 */
@Entity
@Table(name = "user_org_roles")
@IdClass(UserOrgRole.Key.class)
public class UserOrgRole {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "org_id", nullable = false)
    private Long orgId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "user_role")
    private UserRole role;

    @Column(name = "request_mail", nullable = false)
    private boolean requestMail;

    protected UserOrgRole() {
    }

    public UserOrgRole(Long userId, Long orgId, UserRole role) {
        this.userId = userId;
        this.orgId = orgId;
        this.role = role;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public UserRole getRole() {
        return role;
    }

    /**
     * Changes the role. A role that may not approve requests cannot be the
     * one the organisation's request mail goes to, so the choice is dropped
     * with it rather than left for the check constraint to refuse.
     */
    public void setRole(UserRole role) {
        this.role = role;
        if (!mayReceiveRequestMail(role)) {
            this.requestMail = false;
        }
    }

    public boolean isRequestMail() {
        return requestMail;
    }

    public void setRequestMail(boolean requestMail) {
        this.requestMail = requestMail;
    }

    /** The roles that may approve a request, and so may be mailed about one (V133). */
    public static boolean mayReceiveRequestMail(UserRole role) {
        return role == UserRole.ORG_ADMIN || role == UserRole.ORG_MANAGER;
    }

    /** Composite key: one row per (account, organisation). */
    public static class Key implements Serializable {

        private Long userId;
        private Long orgId;

        public Key() {
        }

        public Key(Long userId, Long orgId) {
            this.userId = userId;
            this.orgId = orgId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(userId, key.userId) && Objects.equals(orgId, key.orgId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, orgId);
        }
    }
}
