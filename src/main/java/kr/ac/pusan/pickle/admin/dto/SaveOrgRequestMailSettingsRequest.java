package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.RequestMailMode;
import org.jspecify.annotations.Nullable;

/** Receipt-only input has no role, permission, or source-revision write surface. */
public record SaveOrgRequestMailSettingsRequest(@NotNull @Min(0) Long expectedRevision,
        @NotNull RequestMailMode mailMode, @NotNull @Size(max = 2000) Set<@NotNull UUID> recipientIds,
        @Nullable @Size(max = 2000) String reason) { }
