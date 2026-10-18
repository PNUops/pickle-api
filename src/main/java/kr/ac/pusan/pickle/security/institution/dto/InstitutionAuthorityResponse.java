package kr.ac.pusan.pickle.security.institution.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;
import kr.ac.pusan.pickle.user.UserRole;
import org.jspecify.annotations.Nullable;

/** The current account's authority; SYS role and institution sources remain separate. */
public record InstitutionAuthorityResponse(
        @Schema(allowableValues = {"VALID_EMPTY", "VALID_SOURCES", "INVALID"}) String state,
        @Nullable String failure,
        UserRole accountRole,
        String catalogueRevision,
        @Nullable String authorityFingerprint,
        List<InstitutionGrantView> assignments,
        List<InstitutionWorkView> catalogue) {

    public record InstitutionGrantView(UUID institutionId, String institutionName, InstitutionPermissionView permission) { }
    public record InstitutionWorkView(String id, String name, @Nullable InstitutionWorkGroup group,
            InstitutionWork.Boundary boundary, boolean write, boolean selectable, boolean includedInFull, boolean globalResourceReadInFull) { }
}
