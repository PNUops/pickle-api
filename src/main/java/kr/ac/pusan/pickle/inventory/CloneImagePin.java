package kr.ac.pusan.pickle.inventory;

import java.util.Objects;

/** Immutable clone coordinates, independent of the request's public image identity. */
public record CloneImagePin(long imageId, long nodeId, int templateVmid) {

    public CloneImagePin {
        if (imageId <= 0 || nodeId <= 0 || templateVmid <= 0) {
            throw new IllegalArgumentException("복제할 OS 이미지의 위치가 올바르지 않습니다.");
        }
    }

    public static CloneImagePin from(OsImage replica) {
        if (replica.getId() == null || replica.getNodeId() == null) {
            throw new IllegalArgumentException("등록된 OS 이미지만 복제 대상으로 고정할 수 있습니다.");
        }
        return new CloneImagePin(replica.getId(), replica.getNodeId(), replica.getProxmoxVmid());
    }

    /** A retry never silently follows an overwritten or retired catalog row. */
    public void requireUnchanged(OsImage granted, OsImage replica) {
        if (!Objects.equals(replica.getId(), imageId)
                || !Objects.equals(replica.getNodeId(), nodeId)
                || replica.getProxmoxVmid() != templateVmid
                || replica.getStatus() != CatalogStatus.ACTIVE
                || !OsImageReplicaResolver.compatible(granted, replica)) {
            throw new IllegalStateException("고정된 OS 이미지의 상태나 위치가 변경되었습니다. 관리자 확인이 필요합니다.");
        }
    }
}
