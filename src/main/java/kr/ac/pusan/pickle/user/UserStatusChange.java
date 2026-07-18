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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Account status transition history (M6): admin disable/enable and
 * self-withdrawal. {@code enable} restores the {@code fromStatus} of the
 * matching disable row (contract: enable = restore, never an
 * email-verification bypass) — see V33.
 */
@Entity
@Table(name = "user_status_changes")
public class UserStatusChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "from_status", nullable = false, columnDefinition = "user_status")
    private UserStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "to_status", nullable = false, columnDefinition = "user_status")
    private UserStatus toStatus;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "reason")
    private String reason;

    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected UserStatusChange() {
    }

    public UserStatusChange(Long userId, UserStatus fromStatus, UserStatus toStatus, Long actorId,
            String reason) {
        this.userId = userId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorId = actorId;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public UserStatus getFromStatus() {
        return fromStatus;
    }

    public UserStatus getToStatus() {
        return toStatus;
    }

    public Long getActorId() {
        return actorId;
    }

    public String getReason() {
        return reason;
    }

    public Instant getChangedAt() {
        return changedAt;
    }
}
