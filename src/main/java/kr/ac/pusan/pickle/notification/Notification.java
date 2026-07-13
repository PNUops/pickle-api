package kr.ac.pusan.pickle.notification;

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
 * One in-app notification row — which doubles as the email delivery-log entry
 * (single-table design, docs/plan/02). Rows are INSERTed by
 * {@link NotificationService} (raw SQL for the dedup upsert); this entity is
 * the read/paging side plus the read-receipt writes.
 */
@Entity
@Table(name = "notifications")
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private String event;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Column(name = "link_path")
    private String linkPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationImportance importance = NotificationImportance.NORMAL;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String payload;

    @Column(name = "dedup_key")
    private String dedupKey;

    @Column(name = "announcement_id")
    private Long announcementId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "notification_channel")
    private NotificationChannel channel = NotificationChannel.EMAIL;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "notification_status")
    private NotificationStatus status = NotificationStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "read_at")
    private Instant readAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEvent() {
        return event;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getLinkPath() {
        return linkPath;
    }

    public NotificationImportance getImportance() {
        return importance;
    }

    public Long getAnnouncementId() {
        return announcementId;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public NotificationStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getReadAt() {
        return readAt;
    }

    /** First-write-wins read receipt — a second call keeps the original time. */
    public void markRead(Instant at) {
        if (this.readAt == null) {
            this.readAt = at;
        }
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
