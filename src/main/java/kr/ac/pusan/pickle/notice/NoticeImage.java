package kr.ac.pusan.pickle.notice;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One image attached to a notice body (V92).
 *
 * <p>{@link #data} is loaded whenever this entity is, which is why nothing but
 * the serving path ever fetches the entity: the list and detail paths read
 * {@link NoticeImageMetadata} projections instead. Marking the column
 * {@code @Basic(LAZY)} would not be enough — lazy basic attributes need
 * bytecode enhancement to work at all, so a build without it would silently
 * load every byte of every image on a page of notices.</p>
 */
@Entity
@Table(name = "notice_images")
public class NoticeImage {

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

    @Column(name = "notice_id", nullable = false, updatable = false)
    private Long noticeId;

    @Column(name = "file_name")
    private String fileName;

    /** The type the bytes actually are, not the one the client declared. */
    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "byte_size", nullable = false)
    private int byteSize;

    @Column(nullable = false)
    private byte[] data;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected NoticeImage() {
    }

    public NoticeImage(Long noticeId, String fileName, String contentType, byte[] data,
            int sortOrder) {
        this.noticeId = noticeId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.data = data;
        this.byteSize = data.length;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getNoticeId() {
        return noticeId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContentType() {
        return contentType;
    }

    public int getByteSize() {
        return byteSize;
    }

    public byte[] getData() {
        return data;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
