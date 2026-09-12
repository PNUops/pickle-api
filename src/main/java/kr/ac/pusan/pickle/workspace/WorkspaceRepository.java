package kr.ac.pusan.pickle.workspace;

import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    /**
     * The workspace row, locked — taken by anything that counts what the
     * workspace holds and then adds to it. Counting and inserting are two
     * statements, so without a holder two requests both read the count below
     * the cap and both commit above it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Workspace w where w.id = :id")
    Optional<Workspace> findByIdForUpdate(@Param("id") Long id);


    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<Workspace> findByPublicId(UUID publicId);


    /** A non-deleted workspace by id — deleted workspaces answer empty (masked 404). */
    Optional<Workspace> findByIdAndDeletedAtIsNull(Long id);

    Optional<Workspace> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    /** Workspace targeting / existence check that excludes soft-deleted workspaces. */
    boolean existsByIdAndDeletedAtIsNull(Long id);
}
