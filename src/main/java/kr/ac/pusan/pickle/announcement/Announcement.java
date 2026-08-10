package kr.ac.pusan.pickle.announcement;

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

/**
 * One admin broadcast (V16). The recipient set is snapshotted at send time as
 * individual {@code notifications} rows; {@code recipientCount} records that
 * snapshot's size.
 */
@Entity
@Table(name = "announcements")
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "author_id", nullable = false)
    private Long authorId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AnnouncementScope scope;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "workspace_id")
    private Long workspaceId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(name = "recipient_count", nullable = false)
    private int recipientCount;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Announcement() {
    }

    public Announcement(Long authorId, AnnouncementScope scope, Long orgId, Long workspaceId,
            String title, String body) {
        this.authorId = authorId;
        this.scope = scope;
        this.orgId = orgId;
        this.workspaceId = workspaceId;
        this.title = title;
        this.body = body;
    }

    public Long getId() {
        return id;
    }

    public Long getAuthorId() {
        return authorId;
    }

    public AnnouncementScope getScope() {
        return scope;
    }

    public Long getOrgId() {
        return orgId;
    }

    public Long getWorkspaceId() {
        return workspaceId;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public int getRecipientCount() {
        return recipientCount;
    }

    public void setRecipientCount(int recipientCount) {
        this.recipientCount = recipientCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
