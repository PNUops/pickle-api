package kr.ac.pusan.pickle.notice;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeImageRepository extends JpaRepository<NoticeImage, Long> {

    /**
     * The serving path, and the only query here that loads {@code data}. The
     * notice id is part of the lookup, so an image can never be reached through
     * a notice that does not own it.
     */
    Optional<NoticeImage> findByPublicIdAndNoticeId(UUID publicId, Long noticeId);

    /**
     * Metadata for a page of notices in one query. The return type is a closed
     * projection rather than the entity, which is what keeps the bytes in the
     * database.
     */
    List<NoticeImageMetadata> findByNoticeIdInOrderByNoticeIdAscSortOrderAscIdAsc(
            Collection<Long> noticeIds);

    /** How many images a notice already carries (the per-notice cap). */
    int countByNoticeId(Long noticeId);

    /** Highest {@code sortOrder} in use, so a new image lands after the rest. */
    @Query("select max(i.sortOrder) from NoticeImage i where i.noticeId = :noticeId")
    Integer maxSortOrder(@Param("noticeId") Long noticeId);

    /**
     * Bulk delete rather than load-then-remove: removing an image must not pull
     * its megabytes through the connection first.
     */
    @Modifying
    @Query("delete from NoticeImage i where i.publicId = :imageId and i.noticeId = :noticeId")
    int deleteOwnedImage(@Param("noticeId") Long noticeId, @Param("imageId") UUID imageId);
}
