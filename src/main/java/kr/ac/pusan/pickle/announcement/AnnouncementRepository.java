package kr.ac.pusan.pickle.announcement;

import org.springframework.data.domain.Page;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<Announcement> findByPublicId(UUID publicId);

    Page<Announcement> findAllByOrderByIdDesc(Pageable pageable);

    /**
     * Sender-org visibility for ORG_ADMIN (contract {@code listAnnouncements}):
     * announcements authored by their own org's admins, plus every ALL-scope
     * broadcast. A SYS_ADMIN's WORKSPACE send stays invisible here even when the
     * workspace has members of the caller's org — the recipients see it in their
     * own inboxes instead.
     */
    @Query("""
            select a from Announcement a
             where a.scope = :allScope
                or exists (select 1 from User u where u.id = a.authorId and u.orgId = :orgId)
             order by a.id desc
            """)
    Page<Announcement> findVisibleToOrgAdmin(@Param("allScope") AnnouncementScope allScope,
            @Param("orgId") Long orgId, Pageable pageable);
}
