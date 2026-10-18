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

/** Read-only membership/display projection; the atomic institution writer owns role, payload and revision. */
@Entity
@org.hibernate.annotations.Immutable
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

    public Long getUserId() {
        return userId;
    }

    public Long getOrgId() {
        return orgId;
    }

    public UserRole getRole() {
        return role;
    }

    public boolean isRequestMail() {
        return requestMail;
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
