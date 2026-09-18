package kr.ac.pusan.pickle.inventory;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves the exact granted image revision on the selected node. */
@Service
public class OsImageReplicaResolver {

    private final OsImageRepository images;

    public OsImageReplicaResolver(OsImageRepository images) {
        this.images = images;
    }

    @Transactional(readOnly = true)
    public OsImage resolve(OsImage granted, Long nodeId) {
        return images.findByNameAndVersionAndNodeIdAndStatus(
                        granted.getName(), granted.getVersion(), nodeId, CatalogStatus.ACTIVE)
                .filter(candidate -> compatible(granted, candidate))
                .orElseThrow(() -> new IllegalStateException(
                        "선택한 노드에 승인된 OS 이미지와 동일한 버전이 없습니다 ("
                                + granted.getName() + ", 버전 " + granted.getVersion() + ")"));
    }

    /** A revision may have different template VMIDs, but never different guest semantics. */
    public static boolean compatible(OsImage granted, OsImage candidate) {
        return Objects.equals(granted.getName(), candidate.getName())
                && granted.getVersion() == candidate.getVersion()
                && Objects.equals(granted.getOsFamily(), candidate.getOsFamily())
                && Objects.equals(granted.getOsVersion(), candidate.getOsVersion())
                && Objects.equals(granted.getSshUsername(), candidate.getSshUsername())
                && granted.getMinDiskGb() == candidate.getMinDiskGb();
    }
}
