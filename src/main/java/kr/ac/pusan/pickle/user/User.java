package kr.ac.pusan.pickle.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The identifier this row wears outside the API boundary. Internal joins,
     * sorts and foreign keys keep using {@link #id}.
     */
    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "public_id", nullable = false, updatable = false, unique = true)
    private UUID publicId = UUID.randomUUID();

    @Column(nullable = false, unique = true, columnDefinition = "citext")
    private String email;

    /**
     * Null for an account that has only ever signed in through an external
     * identity. Every comparison against it must handle that. The encoder does
     * not throw on a null hash — it returns false without running BCrypt — so
     * the hazard is a fast answer rather than an error: {@code
     * AuthService.login} burns its timing equaliser instead, or a passwordless
     * account would be identifiable by how quickly it is refused.
     */
    @Column(name = "password_hash")
    private @Nullable String passwordHash;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "user_role")
    private UserRole role = UserRole.USER;

    @Column(name = "org_id")
    private Long orgId;

    /** 직책. Null until the account fills its profile in (V89 adds no backfill). */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(columnDefinition = "user_position")
    private @Nullable UserPosition position;

    /** 학번. Required by {@code chk_users_student_no} only for student positions. */
    @Column(name = "student_no")
    private @Nullable String studentNo;

    /** 소속 학과 코드. Resolved against the catalogue resource, no FK. */
    @Column(name = "department_code")
    private @Nullable String departmentCode;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "user_status")
    private UserStatus status = UserStatus.PENDING_VERIFICATION;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "disabled_reason")
    private String disabledReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected User() {
    }

    public User(String email, String passwordHash, String name) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getEmail() {
        return email;
    }

    /** Null for an account that has never had a password; see {@link #hasPassword()}. */
    public @Nullable String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public Long getOrgId() {
        return orgId;
    }

    public void setOrgId(Long orgId) {
        this.orgId = orgId;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public int getTokenVersion() {
        return tokenVersion;
    }

    public void bumpTokenVersion() {
        this.tokenVersion++;
    }

    public Instant getWithdrawnAt() {
        return withdrawnAt;
    }

    public void setWithdrawnAt(Instant withdrawnAt) {
        this.withdrawnAt = withdrawnAt;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }

    public String getDisabledReason() {
        return disabledReason;
    }

    /** Admin disable: stamps the reason/time (V33). Cleared by {@link #clearDisabled()}. */
    public void disable(String reason, Instant when) {
        this.disabledAt = when;
        this.disabledReason = reason;
    }

    /** Admin enable: clears the disable stamp; history keeps the past reason. */
    public void clearDisabled() {
        this.disabledAt = null;
        this.disabledReason = null;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable UserPosition getPosition() {
        return position;
    }

    public @Nullable String getStudentNo() {
        return studentNo;
    }

    public @Nullable String getDepartmentCode() {
        return departmentCode;
    }

    /**
     * Sets the whole profile at once. It travels as a unit — the student-number
     * rule spans two of the three fields, so a setter per field would let a
     * caller land a state the CHECK rejects and only find out at flush.
     */
    public void setProfile(UserPosition position, @Nullable String studentNo, String departmentCode) {
        this.position = position;
        this.studentNo = studentNo;
        this.departmentCode = departmentCode;
    }

    /** Whether the account has filled in 직책·소속 (and 학번 where required). */
    public boolean isProfileComplete() {
        return position != null && departmentCode != null
                && (!position.requiresStudentNo() || studentNo != null);
    }

    /** Whether a password has ever been set — false for a Google-only account. */
    public boolean hasPassword() {
        return passwordHash != null;
    }

    /**
     * Drops the stored password.
     *
     * <p>For the one case where a password exists but nobody has ever proved
     * they own the mailbox it was set from: an account that is still
     * PENDING_VERIFICATION when its real owner arrives by another route. The
     * holder can set a new one through the reset mail, which is a proof.
     */
    public void clearPassword() {
        this.passwordHash = null;
    }
}
