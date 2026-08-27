package kr.ac.pusan.pickle.notice;

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

/**
 * One notice-board document (V92). Unlike an announcement it is never fanned
 * out — the row itself is what readers open, for as long as its active window
 * lasts.
 *
 * <p>{@link #scope} and {@link #audience} together decide who may open it:
 * an ORG notice belongs to {@link #orgId} and is always
 * {@link NoticeAudience#USERS}, so nothing an organisation writes can be read
 * anonymously.</p>
 */
@Entity
@Table(name = "notices")
public class Notice {

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

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NoticeScope scope;

    @Column(name = "org_id")
    private Long orgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NoticeAudience audience;

    @Column(nullable = false)
    private boolean pinned;

    @Column(nullable = false)
    private boolean popup;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Notice() {
    }

    public Notice(Long createdBy, NoticeScope scope, Long orgId, NoticeAudience audience,
            String title, String body, boolean pinned, boolean popup,
            Instant startsAt, Instant endsAt) {
        this.createdBy = createdBy;
        this.scope = scope;
        this.orgId = orgId;
        this.audience = audience;
        this.title = title;
        this.body = body;
        this.pinned = pinned;
        this.popup = popup;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
    }

    /** Whether the notice is inside its active window at {@code at}. */
    public boolean isActiveAt(Instant at) {
        return !startsAt.isAfter(at) && (endsAt == null || endsAt.isAfter(at));
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public NoticeScope getScope() {
        return scope;
    }

    public Long getOrgId() {
        return orgId;
    }

    public NoticeAudience getAudience() {
        return audience;
    }

    public void setAudience(NoticeAudience audience) {
        this.audience = audience;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public boolean isPopup() {
        return popup;
    }

    public void setPopup(boolean popup) {
        this.popup = popup;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(Instant startsAt) {
        this.startsAt = startsAt;
    }

    public Instant getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(Instant endsAt) {
        this.endsAt = endsAt;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
