package kr.ac.pusan.pickle.workspace;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, Long> {

    /**
     * Slug uniqueness is over live rows only (V33 partial unique index), so a
     * deleted workspace's slug can be reused (contract: deleteWorkspace).
     */
    boolean existsBySlugAndDeletedAtIsNull(String slug);

    /** A non-deleted workspace by id — deleted workspaces answer empty (masked 404). */
    Optional<Workspace> findByIdAndDeletedAtIsNull(Long id);

    /** Workspace targeting / existence check that excludes soft-deleted workspaces. */
    boolean existsByIdAndDeletedAtIsNull(Long id);
}
