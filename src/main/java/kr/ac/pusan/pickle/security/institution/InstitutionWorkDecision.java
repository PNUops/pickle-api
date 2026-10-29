package kr.ac.pusan.pickle.security.institution;

import java.util.List;
import java.util.UUID;

/** All independently qualifying grant sources survive in an allowed decision. */
public record InstitutionWorkDecision(List<GrantSource> sources) {
    public InstitutionWorkDecision { sources = List.copyOf(sources); }

    public boolean allowed() { return !sources.isEmpty(); }

    public record GrantSource(UUID accountId, UUID institutionId, InstitutionAssignment.Mode mode, long revision) { }
}
