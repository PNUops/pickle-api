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

    public void setRole(UserRole role) {
        this.role = role;
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
