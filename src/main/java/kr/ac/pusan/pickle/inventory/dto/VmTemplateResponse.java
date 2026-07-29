package kr.ac.pusan.pickle.inventory.dto;

import kr.ac.pusan.pickle.inventory.TemplateStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code VmTemplate} — OS catalog entry (v0.23.0 axis split). */
public record VmTemplateResponse(
        Long id,
        String name,
        String displayName,
        String osFamily,
        String osVersion,
        String sshUsername,
        int version,
        int minDiskGb,
        TemplateStatus status,
        @Nullable String notes) {

    public static VmTemplateResponse from(OsImage image) {
        return new VmTemplateResponse(image.getId(), image.getName(), image.getDisplayName(),
                image.getOsFamily(), image.getOsVersion(), image.getSshUsername(),
                image.getVersion(), image.getMinDiskGb(), image.getStatus(),
                image.getNotes());
    }
}
