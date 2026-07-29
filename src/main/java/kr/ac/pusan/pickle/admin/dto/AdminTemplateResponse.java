package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.inventory.TemplateStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import org.jspecify.annotations.Nullable;

/**
 * Contract {@code AdminTemplateResponse} (v0.21.0, v0.23.0 axis split): the
 * admin OS-catalog row — unlike the public {@code GET /templates} it carries
 * every status (retired revisions included) plus the operational fields
 * (proxmox vmid, node, notes). Spec presets live in {@code VmFlavor}.
 */
public record AdminTemplateResponse(
        long id,
        String name,
        String displayName,
        String osFamily,
        String osVersion,
        String sshUsername,
        int version,
        int proxmoxVmid,
        Long nodeId,
        TemplateStatus status,
        int minDiskGb,
        @Nullable String notes) {

    public static AdminTemplateResponse from(OsImage image) {
        return new AdminTemplateResponse(image.getId(), image.getName(),
                image.getDisplayName(), image.getOsFamily(), image.getOsVersion(),
                image.getSshUsername(), image.getVersion(), image.getProxmoxVmid(),
                image.getNodeId(), image.getStatus(), image.getMinDiskGb(),
                image.getNotes());
    }
}
