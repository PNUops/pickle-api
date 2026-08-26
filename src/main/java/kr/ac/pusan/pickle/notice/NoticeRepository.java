package kr.ac.pusan.pickle.notice;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoticeRepository extends JpaRepository<Notice, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<Notice> findByPublicId(UUID publicId);

    /**
     * What an anonymous reader may see (contract {@code listNotices}): platform
     * notices published to everyone, inside their active window.
     */
    @Query("""
            select n from Notice n
             where n.startsAt <= :now
               and (n.endsAt is null or n.endsAt > :now)
               and n.scope = kr.ac.pusan.pickle.notice.NoticeScope.PLATFORM
               and n.audience = kr.ac.pusan.pickle.notice.NoticeAudience.PUBLIC
             order by n.pinned desc, n.startsAt desc, n.id desc
            """)
    Page<Notice> findVisibleToAnonymous(@Param("now") Instant now, Pageable pageable);

    /**
     * What a signed-in reader may see: every platform notice regardless of
     * audience, plus their own organisation's. A caller with no organisation
     * passes null, which no {@code org_id} equals, so they simply see the
     * platform rows — the same answer without a second query.
     */
    @Query("""
            select n from Notice n
             where n.startsAt <= :now
               and (n.endsAt is null or n.endsAt > :now)
               and (n.scope = kr.ac.pusan.pickle.notice.NoticeScope.PLATFORM
                    or n.orgId = :orgId)
             order by n.pinned desc, n.startsAt desc, n.id desc
            """)
    Page<Notice> findVisibleToUser(@Param("now") Instant now, @Param("orgId") Long orgId,
            Pageable pageable);

    /** Contract {@code listAdminNotices} for the sys tier: every notice, window or not. */
    @Query("""
            select n from Notice n
             order by n.pinned desc, n.startsAt desc, n.id desc
            """)
    Page<Notice> findAllForAdmin(Pageable pageable);

    /**
     * Contract {@code listAdminNotices} for the org tier: their own
     * organisation's notices plus the platform ones, which they read but
     * cannot write (enforced in {@link NoticeService}).
     */
    @Query("""
            select n from Notice n
             where n.scope = kr.ac.pusan.pickle.notice.NoticeScope.PLATFORM
                or n.orgId = :orgId
             order by n.pinned desc, n.startsAt desc, n.id desc
            """)
    Page<Notice> findForOrgAdmin(@Param("orgId") Long orgId, Pageable pageable);
}
