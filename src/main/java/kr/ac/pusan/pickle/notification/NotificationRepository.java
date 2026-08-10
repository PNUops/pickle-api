package kr.ac.pusan.pickle.notification;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<Notification> findByPublicId(UUID publicId);

    Page<Notification> findByUserIdOrderByCreatedAtDescIdDesc(Long userId, Pageable pageable);

    Page<Notification> findByUserIdAndReadAtIsNullOrderByCreatedAtDescIdDesc(Long userId,
            Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    /** Owner-scoped lookup — other users' rows answer empty (404 masking). */
    Optional<Notification> findByIdAndUserId(Long id, Long userId);
}
