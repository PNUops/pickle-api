package kr.ac.pusan.pickle.inventory.dto;

import kr.ac.pusan.pickle.inventory.TemplateStatus;
import kr.ac.pusan.pickle.inventory.VmTemplate;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code VmTemplate} — OS catalog entry (v0.23.0 axis split). */
public record VmTemplateResponse(
        Long id,
        String name,
        String displayName,
        int version,
        int minDiskGb,
        TemplateStatus status,
        @Nullable String notes) {

    public static VmTemplateResponse from(VmTemplate template) {
        return new VmTemplateResponse(template.getId(), template.getName(), template.getDisplayName(),
                template.getVersion(), template.getMinDiskGb(), template.getStatus(),
                template.getNotes());
    }
}
