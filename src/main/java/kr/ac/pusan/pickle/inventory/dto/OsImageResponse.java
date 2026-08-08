package kr.ac.pusan.pickle.inventory.dto;

import kr.ac.pusan.pickle.inventory.CatalogStatus;
import kr.ac.pusan.pickle.inventory.OsImage;
import org.jspecify.annotations.Nullable;

/** Contract schema {@code OsImage} — OS catalog entry (v0.23.0 axis split). */
public record OsImageResponse(
        Long id,
        String name,
        String displayName,
        String osFamily,
        String osVersion,
        String sshUsername,
        int version,
        int minDiskGb,
        CatalogStatus status,
        @Nullable String notes) {

    public static OsImageResponse from(OsImage image) {
        return new OsImageResponse(image.getId(), image.getName(), image.getDisplayName(),
                image.getOsFamily(), image.getOsVersion(), image.getSshUsername(),
                image.getVersion(), image.getMinDiskGb(), image.getStatus(),
                image.getNotes());
    }
}
