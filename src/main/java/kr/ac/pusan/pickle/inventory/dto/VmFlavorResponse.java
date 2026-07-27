package kr.ac.pusan.pickle.inventory.dto;

import kr.ac.pusan.pickle.inventory.TemplateStatus;
import kr.ac.pusan.pickle.inventory.VmFlavor;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code VmFlavor} — spec preset (v0.23.0 axis split). */
public record VmFlavorResponse(
        Long id,
        String name,
        String displayName,
        int vcpu,
        int memoryMb,
        int diskGb,
        TemplateStatus status,
        @Nullable String notes) {

    public static VmFlavorResponse from(VmFlavor flavor) {
        return new VmFlavorResponse(flavor.getId(), flavor.getName(), flavor.getDisplayName(),
                flavor.getVcpu(), flavor.getMemoryMb(), flavor.getDiskGb(), flavor.getStatus(),
                flavor.getNotes());
    }
}
