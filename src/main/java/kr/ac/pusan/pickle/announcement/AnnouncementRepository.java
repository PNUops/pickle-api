package kr.ac.pusan.pickle.announcement;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<Announcement> findByPublicId(UUID publicId);

    /**
     * Every announcement, newest first. The org tier used to see only what its
     * own organisation's administrators had sent; since 2026-08-25 the listing
     * spans every organisation, so one query serves all admin roles.
     */
    Page<Announcement> findAllByOrderByIdDesc(Pageable pageable);
}
