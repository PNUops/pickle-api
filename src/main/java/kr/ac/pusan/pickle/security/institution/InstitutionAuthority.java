package kr.ac.pusan.pickle.security.institution;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.user.UserRole;

/** Immutable request-time institution authority, independent of personal and SYS authorization. */
public final class InstitutionAuthority {
    private final UUID accountId;
    private final List<InstitutionAssignment> assignments;
    private final String fingerprint;

    public InstitutionAuthority(UUID accountId, Collection<InstitutionAssignment> assignments) {
        this(accountId, assignments, InstitutionWorkCatalogue.descriptor());
    }

    InstitutionAuthority(UUID accountId, Collection<InstitutionAssignment> assignments, String catalogueDescriptor) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.assignments = assignments.stream().sorted(Comparator.comparing(InstitutionAssignment::institutionId)).toList();
        Set<UUID> institutions = new HashSet<>();
        for (InstitutionAssignment assignment : this.assignments) {
            if (!accountId.equals(assignment.accountId()) || !institutions.add(assignment.institutionId())) {
                throw new IllegalArgumentException("Authority requires one assignment per institution for the same account");
            }
        }
        fingerprint = fingerprint(Objects.requireNonNull(catalogueDescriptor, "catalogueDescriptor"));
    }

    public UUID accountId() { return accountId; }
    public List<InstitutionAssignment> assignments() { return assignments; }
    public String fingerprint() { return fingerprint; }

    /** Absence must never be interpreted as a replacement for an account's USER or SYS role. */
    public Optional<UserRole> displayRole() {
        if (assignments.isEmpty()) { return Optional.empty(); }
        if (assignments.stream().anyMatch(assignment -> assignment.mode() == InstitutionAssignment.Mode.FULL)) {
            return Optional.of(UserRole.ORG_ADMIN);
        }
        return Optional.of(assignments.stream().anyMatch(assignment -> assignment.displayRole() == UserRole.ORG_MANAGER)
                ? UserRole.ORG_MANAGER : UserRole.ORG_VIEWER);
    }

    public InstitutionWorkDecision evaluate(String workId, InstitutionWorkTarget target) {
        return InstitutionWork.fromId(workId).map(work -> evaluate(work, target))
                .orElseGet(() -> new InstitutionWorkDecision(List.of()));
    }

    public InstitutionWorkDecision evaluate(InstitutionWork work, InstitutionWorkTarget target) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(target, "target");
        return new InstitutionWorkDecision(assignments.stream()
                .filter(assignment -> assignment.includes(work) && matches(assignment, work, target))
                .map(assignment -> new InstitutionWorkDecision.GrantSource(accountId, assignment.institutionId(),
                        assignment.mode(), assignment.revision()))
                .toList());
    }

    private static boolean matches(InstitutionAssignment assignment, InstitutionWork work, InstitutionWorkTarget target) {
        return switch (work.boundary()) {
            case RESOURCE -> target.institutionId() != null && target.group() == work.group()
                    && assignment.includesWorkspace(work.group(), target.workspaceId())
                    && assignment.institutionId().equals(target.institutionId());
            case RESOURCE_SUPPORT -> target.group() == work.group()
                    && assignment.includesWorkspace(work.group(), target.workspaceId());
            case WORKSPACE_SUPPORT -> target.institutionId() == null && target.group() == InstitutionWorkGroup.WORKSPACE
                    && assignment.includesWorkspace(InstitutionWorkGroup.WORKSPACE, target.workspaceId());
            case QUALIFIED_WORKSPACE -> target.institutionId() == null
                    && target.audienceInstitutions().contains(assignment.institutionId())
                    && target.group() == InstitutionWorkGroup.WORKSPACE
                    && assignment.includesWorkspace(InstitutionWorkGroup.WORKSPACE, target.workspaceId());
            case INSTITUTION -> assignment.institutionId().equals(target.institutionId())
                    && target.group() == null && target.workspaceId() == null;
            case GLOBAL -> target.institutionId() == null && target.group() == null && target.workspaceId() == null;
        };
    }

    private String fingerprint(String catalogueDescriptor) {
        StringBuilder canonical = new StringBuilder("institution-authority:1\n")
                .append(accountId).append('\n').append(catalogueDescriptor.length()).append(':').append(catalogueDescriptor).append('\n');
        for (InstitutionAssignment assignment : assignments) {
            canonical.append(assignment.institutionId()).append(':').append(assignment.revision())
                    .append(':').append(assignment.mode()).append('\n');
            assignment.work().stream().map(InstitutionWork::id).sorted()
                    .forEach(work -> canonical.append("work:").append(work).append('\n'));
            assignment.scopes().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).forEach(entry -> {
                canonical.append("scope:").append(entry.getKey()).append(':').append(entry.getValue().mode()).append('\n');
                entry.getValue().workspaceIds().stream().sorted()
                        .forEach(workspace -> canonical.append("workspace:").append(workspace).append('\n'));
            });
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
