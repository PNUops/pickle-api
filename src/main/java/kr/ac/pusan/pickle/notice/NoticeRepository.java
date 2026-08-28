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
     * What an anonymous reader may see (contract {@code listNotices}).
     *
     * <p>Since V95 this is the entire anonymous boundary, and it is one
     * predicate: {@code popup = true} inside the active window. Nothing else —
     * no organisation, no audience field, no role, no membership — separates a
     * signed-out caller from a signed-in one, so a change to this clause is a
     * change to what the public internet can read.</p>
     */
    @Query("""
            select n from Notice n
             where n.startsAt <= :now
               and (n.endsAt is null or n.endsAt > :now)
               and n.popup = true
             order by n.pinned desc, n.startsAt desc, n.id desc
            """)
    Page<Notice> findVisibleToAnonymous(@Param("now") Instant now, Pageable pageable);

    /**
     * What any signed-in reader may see: every notice inside its active window,
     * popup or not. Being signed in is the whole of the entitlement — one query
     * for every account, because a notice is addressed neither to an
     * organisation nor to an audience.
     */
    @Query("""
            select n from Notice n
             where n.startsAt <= :now
               and (n.endsAt is null or n.endsAt > :now)
             order by n.pinned desc, n.startsAt desc, n.id desc
            """)
    Page<Notice> findVisibleToSignedIn(@Param("now") Instant now, Pageable pageable);

    /**
     * Contract {@code listAdminNotices}: every notice, window or not, for every
     * role the controller's gate admits. There is no narrower admin query since
     * V95 — an organisation no longer owns notices, so there is no subset to
     * scope anyone to.
     */
    @Query("""
            select n from Notice n
             order by n.pinned desc, n.startsAt desc, n.id desc
            """)
    Page<Notice> findAllForAdmin(Pageable pageable);
}
