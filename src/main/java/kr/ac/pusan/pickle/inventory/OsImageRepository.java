package kr.ac.pusan.pickle.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OsImageRepository extends JpaRepository<OsImage, Long> {

    /** Resolution of the identifier this row wears outside the API boundary. */
    Optional<OsImage> findByPublicId(UUID publicId);

    /**
     * Display order of the OS catalog: distribution alphabetically, then release
     * ascending, then id.
     *
     * <p>Insertion order (the id) is an artifact of when an operator registered
     * a row and says nothing to whoever reads the list, so it survives only as
     * the tie-break between rows the first two keys cannot separate (per-node
     * copies and superseded revisions of one release).</p>
     *
     * <p>The version runs newest first. The request form asks for the family
     * before the version, and the answer to "which version" is the current one
     * unless you have a reason -- so the newest sits at the top of that second
     * choice and is what the form preselects. Ascending was right while every
     * image was one card in a single flat list.</p>
     *
     * <p>The release is sorted as the number sequence it is, not as text:
     * {@code '9' > '10'} in text order, which would misplace Rocky the moment
     * it enters the catalog. The {@code chk_os_images_os_version} check
     * constraint (V62) restricts the column to dotted digits, which is what
     * makes this cast total for every row the table can hold.</p>
     */
    String DISPLAY_ORDER =
            " order by os_family asc, string_to_array(os_version, '.')::int[] desc, id asc";

    @Query(value = "select * from os_images where status = cast(:status as catalog_status)"
            + DISPLAY_ORDER, nativeQuery = true)
    List<OsImage> findByStatusInDisplayOrder(@Param("status") String status);

    default List<OsImage> findByStatusInDisplayOrder(CatalogStatus status) {
        return findByStatusInDisplayOrder(status.name());
    }

    @Query(value = "select * from os_images" + DISPLAY_ORDER, nativeQuery = true)
    List<OsImage> findAllInDisplayOrder();

    /** Rows of one status with no display intent — order carries no meaning here. */
    List<OsImage> findByStatus(CatalogStatus status);

    /**
     * Whether a node hosts a usable copy of an OS image. Image rows are
     * per-node (V3); a node "has" the image when it carries a row of the
     * same {@code name} in the given status — the same name-based match node
     * placement uses.
     */
    boolean existsByNameAndNodeIdAndStatus(String name, Long nodeId, CatalogStatus status);
}
