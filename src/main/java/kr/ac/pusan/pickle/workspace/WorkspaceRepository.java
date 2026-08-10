package kr.ac.pusan.pickle.workspace;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<Workspace> findByPublicId(UUID publicId);


    /** A non-deleted workspace by id — deleted workspaces answer empty (masked 404). */
    Optional<Workspace> findByIdAndDeletedAtIsNull(Long id);

    Optional<Workspace> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    /** Workspace targeting / existence check that excludes soft-deleted workspaces. */
    boolean existsByIdAndDeletedAtIsNull(Long id);
}
