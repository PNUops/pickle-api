package kr.ac.pusan.pickle.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.RequestMailMode;
import kr.ac.pusan.pickle.user.UserRole;
import org.jspecify.annotations.Nullable;

public record SaveOrgOperationsRequest(
        @NotNull @Min(0) Long expectedRevision,
        @NotNull @Size(max = 2000) List<@NotNull @Valid Assignment> members,
        @Nullable RequestMailMode mailMode,
        @Nullable @Size(max = 2000) String reason,
        boolean allowVacancy,
        @Nullable UUID confirmedOrgId) {

    public record Assignment(@NotNull UUID userId, @NotNull UserRole role, boolean requestMail) {
    }
}
