package kr.ac.pusan.pickle.workspace;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkspaceInvitationRepository extends JpaRepository<WorkspaceInvitation, Long> {

    Optional<WorkspaceInvitation> findByPublicId(UUID publicId);


    /**
     * Opens a PENDING invitation unless one is already open for the same person
     * in the same workspace. Returns 0 when the partial unique index already
     * holds one, including one a concurrent request inserted a moment ago: the
     * conflict is absorbed by the statement instead of aborting the
     * transaction, so one entry's race cannot undo the rest of a bulk request.
     */
    @Modifying
    @Query(value = """
            insert into workspace_invitations
                   (public_id, workspace_id, invitee_email, invitee_student_no, role, status, invited_by)
            values (:publicId, :workspaceId, :email, :studentNo, cast(:role as workspace_member_role),
                    'PENDING', :invitedBy)
            on conflict do nothing
            """, nativeQuery = true)
    int insertPendingIfAbsent(@Param("publicId") UUID publicId, @Param("workspaceId") long workspaceId,
            @Param("email") @Nullable String email, @Param("studentNo") @Nullable String studentNo,
            @Param("role") String role, @Param("invitedBy") long invitedBy);

    @Query(value = """
            select public_id from workspace_invitations
             where workspace_id = :workspaceId and status = 'PENDING'
               and lower(invitee_email) = lower(:email)
            """, nativeQuery = true)
    Optional<UUID> findPendingIdByEmail(@Param("workspaceId") long workspaceId, @Param("email") String email);

    @Query(value = """
            select public_id from workspace_invitations
             where workspace_id = :workspaceId and status = 'PENDING'
               and upper(invitee_student_no) = upper(:studentNo)
            """, nativeQuery = true)
    Optional<UUID> findPendingIdByStudentNo(@Param("workspaceId") long workspaceId,
            @Param("studentNo") String studentNo);

    /**
     * Closes this workspace's open invitations that name an account which is
     * now a member some other way. Without it a row addressed to the account's
     * email or 학번 would sit in the owner's list forever: its trigger (the
     * activation or the first 학번 save) has already fired.
     */
    @Modifying
    @Query(value = """
            update workspace_invitations
               set status = 'ACCEPTED', accepted_user_id = :userId, accepted_at = now()
             where workspace_id = :workspaceId and status = 'PENDING'
               and (lower(invitee_email) = lower(:email)
                    or (cast(:studentNo as text) is not null
                        and upper(invitee_student_no) = upper(cast(:studentNo as text))))
            """, nativeQuery = true)
    int acceptPendingForMember(@Param("workspaceId") long workspaceId, @Param("userId") long userId,
            @Param("email") String email, @Param("studentNo") @Nullable String studentNo);

    List<WorkspaceInvitation> findByWorkspaceIdAndStatusOrderByCreatedAtAscIdAsc(Long workspaceId,
            WorkspaceInvitationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from WorkspaceInvitation i where i.publicId = :publicId")
    Optional<WorkspaceInvitation> findWithLockByPublicId(@Param("publicId") UUID publicId);

    /**
     * Every open invitation for this address, locked and in id order. The
     * fixed order is what keeps two claims for overlapping rows from
     * deadlocking; the lock is what makes a concurrent cancel and a claim
     * settle on one answer.
     */
    @Query(value = """
            select * from workspace_invitations
             where status = 'PENDING' and lower(invitee_email) = lower(:email)
             order by id
               for update
            """, nativeQuery = true)
    List<WorkspaceInvitation> lockPendingByEmail(@Param("email") String email);

    /** Same as {@link #lockPendingByEmail}, for a 학번, compared as the V131 index does. */
    @Query(value = """
            select * from workspace_invitations
             where status = 'PENDING' and upper(invitee_student_no) = upper(:studentNo)
             order by id
               for update
            """, nativeQuery = true)
    List<WorkspaceInvitation> lockPendingByStudentNo(@Param("studentNo") String studentNo);
}
