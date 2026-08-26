package kr.ac.pusan.pickle.notice;

import java.time.Instant;
import java.util.Collection;
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
     * What a signed-in reader with no organisation may see: every platform
     * notice, whatever its audience. Kept apart from
     * {@link #findVisibleToUser} rather than passing it an empty collection,
     * because an empty {@code in ()} is not something to rely on a dialect to
     * render the way this rule needs.
     */
    @Query("""
            select n from Notice n
             where n.startsAt <= :now
               and (n.endsAt is null or n.endsAt > :now)
               and n.scope = kr.ac.pusan.pickle.notice.NoticeScope.PLATFORM
             order by n.pinned desc, n.startsAt desc, n.id desc
            """)
    Page<Notice> findPlatformVisible(@Param("now") Instant now, Pageable pageable);

    /**
     * What a signed-in reader belonging to at least one organisation may see:
     * every platform notice regardless of audience, plus the notices of the
     * organisations they belong to.
     *
     * <p>{@code orgIds} is the reader's <b>derived</b> membership, resolved by
     * {@link NoticeQueryService} through the canonical rule rather than read off
     * their account, and it must be non-empty.</p>
     */
    @Query("""
            select n from Notice n
             where n.startsAt <= :now
               and (n.endsAt is null or n.endsAt > :now)
               and (n.scope = kr.ac.pusan.pickle.notice.NoticeScope.PLATFORM
                    or n.orgId in :orgIds)
             order by n.pinned desc, n.startsAt desc, n.id desc
            """)
    Page<Notice> findVisibleToUser(@Param("now") Instant now,
            @Param("orgIds") Collection<Long> orgIds, Pageable pageable);

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
