package kr.ac.pusan.pickle.notice;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Where a notice's images live.
 *
 * <p>The notice paths know images only through this interface, so moving the
 * bytes out of the database later — to a file on the host or to object storage
 * — is one new implementation and nothing else. That is why the vocabulary here
 * is {@link NoticeImageMeta} and {@link NoticeImageContent} rather than the JPA
 * entity: nothing in the signature assumes the bytes are a column.</p>
 *
 * <p>Every method takes the owning notice's internal id, so an image can only
 * ever be reached through the notice that owns it. A store never decides who
 * may see a notice — {@link NoticeQueryService} has already settled that by the
 * time it is called.</p>
 */
public interface NoticeImageStore {

    /**
     * Stores one image against a notice and returns what was written.
     * {@code contentType} is the type the bytes were determined to be, and
     * validating that is the caller's job.
     */
    NoticeImageMeta store(long noticeId, String fileName, String contentType, byte[] bytes);

    /**
     * Metadata for a whole page of notices, keyed by notice id, in display
     * order. Notices with no images are simply absent from the map. One call,
     * so a list of notices costs one query rather than one per row.
     */
    Map<Long, List<NoticeImageMeta>> metadataByNotice(Collection<Long> noticeIds);

    /** The bytes of one image, or empty when the notice does not own it. */
    Optional<NoticeImageContent> load(long noticeId, UUID imageId);

    /** Removes one image; false when the notice does not own it. */
    boolean delete(long noticeId, UUID imageId);

    /** How many images the notice already carries, for the per-notice cap. */
    int count(long noticeId);
}
