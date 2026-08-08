package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import org.jspecify.annotations.Nullable;

/**
 * Contract {@code AdminOsImageResponse} (v0.21.0, v0.23.0 axis split): the
 * admin OS-catalog row — unlike the public {@code GET /os-images} it carries
 * every status (retired revisions included) plus the operational fields
 * (proxmox vmid, node, notes). Spec presets live in {@code VmFlavor}.
 */
public record AdminOsImageResponse(
        long id,
        String name,
        String displayName,
        String osFamily,
        String osVersion,
        String sshUsername,
        int version,
        int proxmoxVmid,
        Long nodeId,
        CatalogStatus status,
        int minDiskGb,
        @Nullable String notes) {

    public static AdminOsImageResponse from(OsImage image) {
        return new AdminOsImageResponse(image.getId(), image.getName(),
                image.getDisplayName(), image.getOsFamily(), image.getOsVersion(),
                image.getSshUsername(), image.getVersion(), image.getProxmoxVmid(),
                image.getNodeId(), image.getStatus(), image.getMinDiskGb(),
                image.getNotes());
    }
}
