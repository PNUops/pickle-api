package kr.ac.pusan.pickle.inventory;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Selects logical catalog identities while retaining every node-specific inventory row. */
public final class OsImageSelection {

    private OsImageSelection() {
    }

    /** The input contains all rows in display order, including disabled canonical identities. */
    public static List<OsImage> selectable(List<OsImage> allCatalogRows, Set<Long> activeNodeIds) {
        Set<Long> canonicalIds = groups(allCatalogRows).values().stream()
                .filter(rows -> hasAvailableReplica(canonical(rows), rows, activeNodeIds))
                .map(rows -> canonical(rows).getId())
                .collect(Collectors.toSet());
        return allCatalogRows.stream().filter(image -> canonicalIds.contains(image.getId())).toList();
    }

    public static boolean isSelectable(OsImage requested, List<OsImage> allCatalogRows, Set<Long> activeNodeIds) {
        List<OsImage> rows = groups(allCatalogRows).get(identity(requested));
        if (rows == null) {
            return false;
        }
        OsImage canonical = canonical(rows);
        return OsImageReplicaResolver.compatible(canonical, requested)
                && hasAvailableReplica(canonical, rows, activeNodeIds);
    }

    public static Optional<OsImage> canonicalOf(OsImage requested, List<OsImage> allCatalogRows) {
        List<OsImage> rows = groups(allCatalogRows).get(identity(requested));
        return rows == null ? Optional.empty() : Optional.of(canonical(rows));
    }

    private static boolean hasAvailableReplica(OsImage canonical, List<OsImage> rows, Set<Long> activeNodeIds) {
        return rows.stream().anyMatch(image -> image.getStatus() == CatalogStatus.ACTIVE
                && activeNodeIds.contains(image.getNodeId()) && OsImageReplicaResolver.compatible(canonical, image));
    }

    private static OsImage canonical(List<OsImage> rows) {
        return rows.stream().min(Comparator.comparing(OsImage::getId)).orElseThrow();
    }

    private static Map<OsImageRevisionIdentity, List<OsImage>> groups(List<OsImage> rows) {
        if (rows.stream().anyMatch(image -> image.getId() == null)) {
            throw new IllegalArgumentException("등록되지 않은 이미지는 카탈로그에서 선택할 수 없습니다.");
        }
        return rows.stream().collect(Collectors.groupingBy(OsImageSelection::identity,
                LinkedHashMap::new, Collectors.toList()));
    }

    private static OsImageRevisionIdentity identity(OsImage image) {
        return new OsImageRevisionIdentity(image.getName(), image.getVersion());
    }

    private record OsImageRevisionIdentity(String name, int version) {
    }
}
