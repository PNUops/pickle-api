package kr.ac.pusan.pickle.security.institution;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * Source constraints for four fixed query bindings, never an endpoint or row-access decision.
 * A server adapter must resolve actual relations and apply every institution and workspace
 * constraint before selecting rows or references. Nonempty constraints alone do not admit a query.
 */
public final class InstitutionQuerySourceConstraints {
    private final InstitutionQueryBinding binding;
    private final String sourceAuthorityFingerprint;
    private final List<InstitutionQuerySourceConstraint> sources;

    private InstitutionQuerySourceConstraints(InstitutionAuthority authority, InstitutionQueryBinding binding,
            List<InstitutionQuerySourceConstraint> sources) {
        this.binding = binding;
        sourceAuthorityFingerprint = authority.fingerprint();
        this.sources = List.copyOf(sources);
    }

    public static InstitutionQuerySourceConstraints vmAllocationSources(InstitutionAuthority authority) {
        return collect(authority, InstitutionQueryBinding.VM_ALLOCATION, null);
    }

    public static InstitutionQuerySourceConstraints llmObservationSources(InstitutionAuthority authority) {
        return collect(authority, InstitutionQueryBinding.LLM_OBSERVATION, null);
    }

    public static InstitutionQuerySourceConstraints llmServiceReferenceSources(InstitutionAuthority authority,
            InstitutionLlmServiceReferenceFacts resolvedFacts) {
        return collect(authority, InstitutionQueryBinding.LLM_SERVICE_REFERENCE, Objects.requireNonNull(resolvedFacts));
    }

    public static InstitutionQuerySourceConstraints llmAggregateMoneySources(InstitutionAuthority authority) {
        return collect(authority, InstitutionQueryBinding.LLM_AGGREGATE_MONEY, null);
    }

    public InstitutionQueryBinding binding() { return binding; }

    /** Source/catalogue context only, not a result-cache key for query filters or resolved facts. */
    public String sourceAuthorityFingerprint() { return sourceAuthorityFingerprint; }

    public List<InstitutionQuerySourceConstraint> sources() { return sources; }

    private static InstitutionQuerySourceConstraints collect(InstitutionAuthority authority,
            InstitutionQueryBinding binding, @Nullable InstitutionLlmServiceReferenceFacts facts) {
        Objects.requireNonNull(authority, "authority");
        List<InstitutionQuerySourceConstraint> sources = new ArrayList<>();
        for (InstitutionAssignment assignment : authority.assignments()) {
            if (!binding.hasRequiredWork(assignment)) { continue; }
            InstitutionWorkspaceScope assignedScope = assignment.mode() == InstitutionAssignment.Mode.FULL
                    ? InstitutionWorkspaceScope.all() : assignment.scopes().get(binding.group());
            InstitutionWorkspaceScope queryScope = assignedScope;
            if (binding == InstitutionQueryBinding.LLM_SERVICE_REFERENCE) {
                InstitutionLlmServiceReferenceFacts resolved = Objects.requireNonNull(facts);
                if (!resolved.existingInstitutionIds().contains(assignment.institutionId())) { continue; }
                queryScope = resolved.referenceScope(assignment.institutionId(), assignedScope);
            }
            if (queryScope.mode() == InstitutionWorkspaceScope.Mode.LISTED && queryScope.workspaceIds().isEmpty()) { continue; }
            sources.add(new InstitutionQuerySourceConstraint(assignment.accountId(), assignment.institutionId(),
                    assignment.mode(), assignment.revision(), binding.group(), assignedScope, queryScope));
        }
        return new InstitutionQuerySourceConstraints(authority, binding, sources);
    }

    /** An institution is both the grant source and a required equality on this binding's rows. */
    public record InstitutionQuerySourceConstraint(UUID accountId, UUID sourceInstitutionId,
            InstitutionAssignment.Mode mode, long revision, InstitutionWorkGroup group,
            InstitutionWorkspaceScope assignedWorkspaceScope, InstitutionWorkspaceScope workspaceScope) {
        public InstitutionQuerySourceConstraint {
            Objects.requireNonNull(accountId); Objects.requireNonNull(sourceInstitutionId);
            Objects.requireNonNull(mode); Objects.requireNonNull(group);
            Objects.requireNonNull(assignedWorkspaceScope); Objects.requireNonNull(workspaceScope);
            if (revision < 0) { throw new IllegalArgumentException("revision must not be negative"); }
            if (mode == InstitutionAssignment.Mode.FULL && assignedWorkspaceScope.mode() != InstitutionWorkspaceScope.Mode.ALL) {
                throw new IllegalArgumentException("FULL requires an assigned ALL scope");
            }
            if (assignedWorkspaceScope.mode() == InstitutionWorkspaceScope.Mode.LISTED
                    && (workspaceScope.mode() != InstitutionWorkspaceScope.Mode.LISTED
                        || !assignedWorkspaceScope.workspaceIds().containsAll(workspaceScope.workspaceIds()))) {
                throw new IllegalArgumentException("Query workspace scope must not exceed its assignment");
            }
        }

        public UUID requiredInstitutionId() { return sourceInstitutionId; }
    }

    /**
     * Server-resolver facts, not client assertions or proof manufactured from UUID presence.
     * The resolver must verify institution existence and each actual LLM request/key relation
     * to a live workspace. Personal membership alone is not such a relation. No DB lookup
     * or liveness verification is performed by these immutable values.
     */
    public record InstitutionLlmServiceReferenceFacts(Set<UUID> existingInstitutionIds,
            Set<InstitutionLlmServiceRelation> liveLlmRelations) {
        public InstitutionLlmServiceReferenceFacts {
            existingInstitutionIds = Set.copyOf(existingInstitutionIds);
            liveLlmRelations = Set.copyOf(liveLlmRelations);
            Set<String> identities = new HashSet<>();
            for (InstitutionLlmServiceRelation relation : liveLlmRelations) {
                if (!existingInstitutionIds.contains(relation.institutionId())) {
                    throw new IllegalArgumentException("A resolved LLM relation requires its existing institution");
                }
                if (!identities.add(relation.kind() + ":" + relation.resourceId())) {
                    throw new IllegalArgumentException("One LLM object must have one resolved institution and workspace");
                }
            }
        }

        private InstitutionWorkspaceScope referenceScope(UUID institutionId, InstitutionWorkspaceScope scope) {
            if (scope.mode() == InstitutionWorkspaceScope.Mode.ALL) { return scope; }
            Set<UUID> workspaces = liveLlmRelations.stream()
                    .filter(relation -> institutionId.equals(relation.institutionId()))
                    .map(InstitutionLlmServiceRelation::workspaceId).filter(scope::includes).collect(Collectors.toSet());
            return InstitutionWorkspaceScope.listed(workspaces);
        }
    }

    public record InstitutionLlmServiceRelation(InstitutionLlmServiceRelationKind kind, UUID resourceId,
            UUID institutionId, UUID workspaceId) {
        public InstitutionLlmServiceRelation {
            Objects.requireNonNull(kind); Objects.requireNonNull(resourceId);
            Objects.requireNonNull(institutionId); Objects.requireNonNull(workspaceId);
        }
    }

    public enum InstitutionLlmServiceRelationKind { LLM_REQUEST, LLM_KEY }

    public enum InstitutionQueryBinding {
        VM_ALLOCATION("vm-allocation", InstitutionWorkGroup.VM),
        LLM_OBSERVATION("llm-observation", InstitutionWorkGroup.LLM),
        LLM_SERVICE_REFERENCE("llm-service-reference", InstitutionWorkGroup.LLM),
        LLM_AGGREGATE_MONEY("llm-aggregate-money", InstitutionWorkGroup.LLM);

        private final String id;
        private final InstitutionWorkGroup group;

        InstitutionQueryBinding(String id, InstitutionWorkGroup group) { this.id = id; this.group = group; }

        public String id() { return id; }
        public InstitutionWorkGroup group() { return group; }

        private boolean hasRequiredWork(InstitutionAssignment assignment) {
            return switch (this) {
                case VM_ALLOCATION -> assignment.includes(InstitutionWork.VM_OBSERVE);
                case LLM_OBSERVATION, LLM_SERVICE_REFERENCE -> assignment.includes(InstitutionWork.LLM_OBSERVE);
                case LLM_AGGREGATE_MONEY -> assignment.includes(InstitutionWork.LLM_OBSERVE)
                        && assignment.includes(InstitutionWork.LLM_KEY_COST_READ);
            };
        }

        private String descriptor() {
            String work = switch (this) {
                case VM_ALLOCATION -> "vm.observe";
                case LLM_OBSERVATION, LLM_SERVICE_REFERENCE -> "llm.observe";
                case LLM_AGGREGATE_MONEY -> "llm.observe+llm.key.cost.read";
            };
            String relations = switch (this) {
                case VM_ALLOCATION -> "allocation-history+current-capacity-reference+all-zero-resources";
                case LLM_OBSERVATION -> "scoped-key-rows";
                case LLM_SERVICE_REFERENCE -> "server-existing-institution+listed-live-llm-request-or-key+all-zero-resources";
                case LLM_AGGREGATE_MONEY -> "same-assignment-work+no-full-global-key-cost-exception";
            };
            return "binding:" + id + ":" + group + ":" + work + ":source-actual-institution+group-workspace:"
                    + relations + ":empty-listed-deny:constraints-only:v1";
        }
    }

    static String bindingDescriptor() {
        return Arrays.stream(InstitutionQueryBinding.values()).map(InstitutionQueryBinding::descriptor)
                .collect(Collectors.joining("\n"));
    }
}
