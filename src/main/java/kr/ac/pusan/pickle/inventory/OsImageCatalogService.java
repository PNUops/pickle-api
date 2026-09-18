package kr.ac.pusan.pickle.inventory;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Public logical catalog over node-specific image inventory rows. */
@Service
public class OsImageCatalogService {

    private final OsImageRepository images;
    private final NodeRepository nodes;

    public OsImageCatalogService(OsImageRepository images, NodeRepository nodes) {
        this.images = images;
        this.nodes = nodes;
    }

    @Transactional(readOnly = true)
    public List<OsImage> selectable() {
        List<OsImage> rows = images.findAllInDisplayOrder();
        return OsImageSelection.selectable(rows, activeNodeIds());
    }

    @Transactional(readOnly = true)
    public OsImage requireSelectable(UUID publicId) {
        OsImage requested = images.findByPublicId(publicId)
                .orElseThrow(() -> new UnknownImageException());
        List<OsImage> rows = images.findAllInDisplayOrder();
        OsImage canonical = OsImageSelection.canonicalOf(requested, rows)
                .orElseThrow(UnknownImageException::new);
        if (!OsImageSelection.isSelectable(requested, rows, activeNodeIds())) {
            throw new UnavailableImageException();
        }
        return canonical;
    }

    private Set<Long> activeNodeIds() {
        return nodes.findByStatusOrderByIdAsc(NodeStatus.ACTIVE).stream()
                .map(Node::getId).collect(Collectors.toSet());
    }

    public static final class UnknownImageException extends RuntimeException {
    }

    public static final class UnavailableImageException extends RuntimeException {
    }
}
