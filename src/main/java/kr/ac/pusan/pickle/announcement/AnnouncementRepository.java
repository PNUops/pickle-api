package kr.ac.pusan.pickle.announcement;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<Announcement> findByPublicId(UUID publicId);

    /** Every announcement, newest first. The sys tier's view. */
    Page<Announcement> findAllByOrderByIdDesc(Pageable pageable);

    /**
     * Sender-org visibility for the org tier: announcements authored by an
     * administrator of an organisation this account holds a role in, plus every
     * ALL-scope broadcast. A SYS_ADMIN's WORKSPACE send stays invisible here
     * even when the workspace has members of those organisations — the
     * recipients see it in their own inboxes instead.
     */
    @Query("""
            select a from Announcement a
             where a.scope = :allScope
                or exists (select 1 from UserOrgRole r
                            where r.userId = a.authorId and r.orgId in :orgIds)
             order by a.id desc
            """)
    Page<Announcement> findVisibleToOrgAdmin(@Param("allScope") AnnouncementScope allScope,
            @Param("orgIds") Collection<Long> orgIds, Pageable pageable);
}
