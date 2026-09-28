package kr.ac.pusan.pickle.request;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RequestRecipientRepository extends JpaRepository<RequestRecipient, Long> {

    List<RequestRecipient> findByRequestIdOrderByIdAsc(Long requestId);

    List<RequestRecipient> findByRequestIdInOrderByIdAsc(Collection<Long> requestIds);

    boolean existsByRequestId(Long requestId);

    Optional<RequestRecipient> findByPublicIdAndRequestId(UUID publicId, Long requestId);

    /** Row-locked read of one recipient, for the per-recipient creation transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RequestRecipient r where r.id = :id")
    Optional<RequestRecipient> findWithLockById(@Param("id") Long id);

    /** Row-locked reads of a request's recipients (approval, cancel, retry). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RequestRecipient r where r.requestId = :requestId order by r.id")
    List<RequestRecipient> findWithLockByRequestId(@Param("requestId") Long requestId);

    /** The invitation's rows still waiting for the invitee, locked (the claim hook). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from RequestRecipient r
             where r.invitationId = :invitationId and r.status = :status
             order by r.id
            """)
    List<RequestRecipient> findWithLockByInvitationIdAndStatus(@Param("invitationId") Long invitationId,
            @Param("status") RequestRecipientStatus status);
}
