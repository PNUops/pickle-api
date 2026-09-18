package kr.ac.pusan.pickle.inventory;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable clone coordinates, independent of the request's public image identity. */
public record CloneImagePin(long imageId, long nodeId, int templateVmid, String revisionSha256) {

    public CloneImagePin {
        if (imageId <= 0 || nodeId <= 0 || templateVmid <= 0 || revisionSha256 == null
                || !revisionSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("복제할 OS 이미지의 위치가 올바르지 않습니다.");
        }
    }

    public static CloneImagePin from(OsImage replica) {
        if (replica.getId() == null || replica.getNodeId() == null) {
            throw new IllegalArgumentException("등록된 OS 이미지만 복제 대상으로 고정할 수 있습니다.");
        }
        return new CloneImagePin(replica.getId(), replica.getNodeId(), replica.getProxmoxVmid(),
                revisionSha256(replica));
    }

    /** A retry never silently follows an overwritten or retired catalog row. */
    public void requireUnchanged(OsImage granted, OsImage replica) {
        if (!Objects.equals(replica.getId(), imageId)
                || !Objects.equals(replica.getNodeId(), nodeId)
                || replica.getProxmoxVmid() != templateVmid
                || replica.getStatus() != CatalogStatus.ACTIVE
                || !revisionSha256.equals(revisionSha256(granted))
                || !revisionSha256.equals(revisionSha256(replica))
                || !OsImageReplicaResolver.compatible(granted, replica)) {
            throw new IllegalStateException("고정된 OS 이미지의 상태나 위치가 변경되었습니다. 관리자 확인이 필요합니다.");
        }
    }

    /** Creation provenance survives recovery; it must never authorize a clone on another current node. */
    public void requireCurrentNode(long currentNodeId) {
        if (currentNodeId != nodeId) {
            throw new IllegalStateException("현재 VM 위치가 최초 복제 위치와 다릅니다. 다시 복제할 수 없습니다.");
        }
    }

    /** Identifies registered revision metadata, not the mutable contents of a hypervisor disk. */
    public static String revisionSha256(OsImage image) {
        List<String> fields = List.of("pickle-clone-revision-v1", image.getName(),
                Integer.toString(image.getVersion()), image.getOsFamily(), image.getOsVersion(),
                image.getSshUsername(), Integer.toString(image.getMinDiskGb()));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] value = field.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                digest.update(value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("이미지 식별 정보를 계산할 수 없습니다.", impossible);
        }
    }
}
