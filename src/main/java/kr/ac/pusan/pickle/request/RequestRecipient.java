package kr.ac.pusan.pickle.request;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One person a request asks a resource for (V133). A request without any of
 * these is the ordinary request whose requester owns the result.
 */
@Entity
@Table(name = "request_recipients")
public class RequestRecipient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.UUID)
    @Column(name = "public_id", nullable = false, updatable = false, unique = true)
    private UUID publicId = UUID.randomUUID();

    @Column(name = "request_id", nullable = false, updatable = false)
    private Long requestId;

    @Column(name = "user_id")
    private @Nullable Long userId;

    @Column(name = "invitation_id", updatable = false)
    private @Nullable Long invitationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestRecipientStatus status;

    @Column(name = "resource_id")
    private @Nullable Long resourceId;

    @Column
    private @Nullable String reason;

    @Column(nullable = false)
    private int attempts;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RequestRecipient() {
    }

    public RequestRecipient(Long requestId, @Nullable Long userId, @Nullable Long invitationId,
            RequestRecipientStatus status) {
        this.requestId = requestId;
        this.userId = userId;
        this.invitationId = invitationId;
        this.status = status;
    }

    /** Moves to a state that carries no resource, with an optional reason. */
    public void mark(RequestRecipientStatus status, @Nullable String reason) {
        this.status = status;
        this.reason = reason;
    }

    public void created(long resourceId) {
        this.status = RequestRecipientStatus.CREATED;
        this.resourceId = resourceId;
        this.reason = null;
    }

    public void failed(String reason) {
        this.status = RequestRecipientStatus.FAILED;
        this.reason = reason;
        this.attempts++;
    }

    /** The invitee joined: from here on the row names an account. */
    public void joined(long userId) {
        this.userId = userId;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getRequestId() {
        return requestId;
    }

    public @Nullable Long getUserId() {
        return userId;
    }

    public @Nullable Long getInvitationId() {
        return invitationId;
    }

    public RequestRecipientStatus getStatus() {
        return status;
    }

    public @Nullable Long getResourceId() {
        return resourceId;
    }

    public @Nullable String getReason() {
        return reason;
    }

    public int getAttempts() {
        return attempts;
    }
}
