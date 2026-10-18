package kr.ac.pusan.pickle.security.institution;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** One complete source branch; query adapters must apply it before counts, paging or aggregation. */
public record InstitutionAuthorizationSource(InstitutionWorkDecision.GrantSource source, InstitutionWork work,
        InstitutionWork.Boundary boundary, @Nullable InstitutionWorkGroup group,
        @Nullable UUID requiredInstitutionId, @Nullable InstitutionWorkspaceScope workspaceScope,
        boolean fullGlobalResourceRead) { }
