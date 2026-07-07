package kr.ac.pusan.pickle.inventory.dto;

import kr.ac.pusan.pickle.inventory.TemplateStatus;
import kr.ac.pusan.pickle.inventory.VmTemplate;

/** Contract schema {@code VmTemplate}. */
public record VmTemplateResponse(
        Long id,
        String name,
        String displayName,
        int version,
        int defaultVcpu,
        int defaultMemoryMb,
        int defaultDiskGb,
        int minDiskGb,
        TemplateStatus status,
        String notes) {

    public static VmTemplateResponse from(VmTemplate template) {
        return new VmTemplateResponse(template.getId(), template.getName(), template.getDisplayName(),
                template.getVersion(), template.getDefaultVcpu(), template.getDefaultMemoryMb(),
                template.getDefaultDiskGb(), template.getMinDiskGb(), template.getStatus(),
                template.getNotes());
    }
}
