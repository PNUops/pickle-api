package kr.ac.pusan.pickle.workspace;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * An owner's invitation of one person, named by email or by 학번, into a
 * workspace. Rows are inserted by {@link WorkspaceInvitationRepository#insertPendingIfAbsent}
 * rather than through this entity, so a concurrent duplicate lands on the
 * partial unique index as "nothing inserted" instead of an exception.
 */
@Entity
@Table(name = "workspace_invitations")
public class WorkspaceInvitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "public_id", nullable = false, updatable = false, unique = true)
    private UUID publicId;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private Long workspaceId;

    @Column(name = "invitee_email", updatable = false, columnDefinition = "citext")
    private @Nullable String inviteeEmail;

    @Column(name = "invitee_student_no", updatable = false)
    private @Nullable String inviteeStudentNo;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, updatable = false, columnDefinition = "workspace_member_role")
    private WorkspaceMemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WorkspaceInvitationStatus status;

    @Column(name = "invited_by", nullable = false, updatable = false)
    private Long invitedBy;

    @Column(name = "accepted_user_id")
    private @Nullable Long acceptedUserId;

    @Column(name = "accepted_at")
    private @Nullable Instant acceptedAt;

    @Column(name = "canceled_by")
    private @Nullable Long canceledBy;

    @Column(name = "canceled_at")
    private @Nullable Instant canceledAt;

    @Column(name = "created_at", nullable = false, updatable = false, insertable = false)
    private Instant createdAt;

    protected WorkspaceInvitation() {
    }

    public void accept(long userId, Instant when) {
        this.status = WorkspaceInvitationStatus.ACCEPTED;
        this.acceptedUserId = userId;
        this.acceptedAt = when;
    }

    public void cancel(long actorId, Instant when) {
        this.status = WorkspaceInvitationStatus.CANCELED;
        this.canceledBy = actorId;
        this.canceledAt = when;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public @Nullable String getInviteeEmail() {
        return inviteeEmail;
    }

    public @Nullable String getInviteeStudentNo() {
        return inviteeStudentNo;
    }

    public WorkspaceMemberRole getRole() {
        return role;
    }

    public WorkspaceInvitationStatus getStatus() {
        return status;
    }

    public @Nullable Long getAcceptedUserId() {
        return acceptedUserId;
    }

    public Long getInvitedBy() {
        return invitedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
