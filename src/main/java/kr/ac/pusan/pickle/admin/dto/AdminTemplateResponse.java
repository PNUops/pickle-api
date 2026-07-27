package kr.ac.pusan.pickle.admin.dto;

import kr.ac.pusan.pickle.inventory.TemplateStatus;
import kr.ac.pusan.pickle.inventory.VmTemplate;
import org.jspecify.annotations.Nullable;

/**
 * Contract {@code AdminTemplateResponse} (v0.21.0): the admin template list
 * row — unlike the public {@code GET /templates} it carries every status
 * (retired revisions included) plus the operational fields (proxmox vmid,
 * node, notes).
 */
public record AdminTemplateResponse(
        long id,
        String name,
        String displayName,
        int version,
        int proxmoxVmid,
        Long nodeId,
        TemplateStatus status,
        int defaultVcpu,
        int defaultMemoryMb,
        int defaultDiskGb,
        int minDiskGb,
        @Nullable String notes) {

    public static AdminTemplateResponse from(VmTemplate template) {
        return new AdminTemplateResponse(template.getId(), template.getName(),
                template.getDisplayName(), template.getVersion(), template.getProxmoxVmid(),
                template.getNodeId(), template.getStatus(), template.getDefaultVcpu(),
                template.getDefaultMemoryMb(), template.getDefaultDiskGb(),
                template.getMinDiskGb(), template.getNotes());
    }
}
