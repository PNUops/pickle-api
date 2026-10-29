package kr.ac.pusan.pickle.security.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.security.institution.InstitutionQuerySourceConstraints.InstitutionLlmServiceReferenceFacts;
import kr.ac.pusan.pickle.security.institution.InstitutionQuerySourceConstraints.InstitutionLlmServiceRelation;
import kr.ac.pusan.pickle.security.institution.InstitutionQuerySourceConstraints.InstitutionLlmServiceRelationKind;
import kr.ac.pusan.pickle.security.institution.InstitutionQuerySourceConstraints.InstitutionQueryBinding;
import kr.ac.pusan.pickle.security.institution.InstitutionQuerySourceConstraints.InstitutionQuerySourceConstraint;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.Test;

class InstitutionQuerySourceConstraintsTest {
    private static final UUID ACCOUNT = id(1);
    private static final UUID FIRST_ORG = id(2);
    private static final UUID SECOND_ORG = id(3);
    private static final UUID THIRD_ORG = id(4);
    private static final UUID FIRST_WORKSPACE = id(5);
    private static final UUID SECOND_WORKSPACE = id(6);
    private static final UUID THIRD_WORKSPACE = id(7);

    @Test
    void vmAllocationPreservesCompleteSourcesAndAllScopeWithoutAnyResourceRows() {
        InstitutionAuthority authority = authority(selected(FIRST_ORG, Set.of(InstitutionWork.VM_OBSERVE),
                InstitutionWorkGroup.VM, InstitutionWorkspaceScope.all(), 11),
                InstitutionAssignment.full(ACCOUNT, SECOND_ORG, 13));
        InstitutionQuerySourceConstraints constraints = InstitutionQuerySourceConstraints.vmAllocationSources(authority);

        assertThat(constraints.binding()).isEqualTo(InstitutionQueryBinding.VM_ALLOCATION);
        assertThat(constraints.sourceAuthorityFingerprint()).isEqualTo(authority.fingerprint());
        assertThat(constraints.sources()).containsExactly(
                new InstitutionQuerySourceConstraint(ACCOUNT, FIRST_ORG, InstitutionAssignment.Mode.SELECTED, 11,
                        InstitutionWorkGroup.VM, InstitutionWorkspaceScope.all(), InstitutionWorkspaceScope.all()),
                new InstitutionQuerySourceConstraint(ACCOUNT, SECOND_ORG, InstitutionAssignment.Mode.FULL, 13,
                        InstitutionWorkGroup.VM, InstitutionWorkspaceScope.all(), InstitutionWorkspaceScope.all()));
    }

    @Test
    void vmAllocationCannotBorrowMetadataWorkOrAnotherSourcesWorkspaceScope() {
        InstitutionAuthority authority = authority(
                selected(FIRST_ORG, Set.of(InstitutionWork.VM_OBSERVE), InstitutionWorkGroup.VM, listed(FIRST_WORKSPACE), 11),
                selected(SECOND_ORG, Set.of(InstitutionWork.VM_METADATA_SUPPORT_READ), InstitutionWorkGroup.VM, listed(SECOND_WORKSPACE), 13),
                selected(THIRD_ORG, Set.of(InstitutionWork.VM_OBSERVE), InstitutionWorkGroup.VM, listed(THIRD_WORKSPACE), 17));
        List<InstitutionQuerySourceConstraint> sources = InstitutionQuerySourceConstraints.vmAllocationSources(authority).sources();

        assertThat(sources).extracting(InstitutionQuerySourceConstraint::requiredInstitutionId).containsExactly(FIRST_ORG, THIRD_ORG);
        assertThat(sources).extracting(InstitutionQuerySourceConstraint::workspaceScope)
                .containsExactly(listed(FIRST_WORKSPACE), listed(THIRD_WORKSPACE));
    }

    @Test
    void emptyListedScopesDoNotBecomeAllInAnyBinding() {
        InstitutionAuthority vm = authority(selected(FIRST_ORG, Set.of(InstitutionWork.VM_OBSERVE),
                InstitutionWorkGroup.VM, listed(), 11));
        InstitutionAuthority llm = authority(selected(FIRST_ORG,
                Set.of(InstitutionWork.LLM_OBSERVE, InstitutionWork.LLM_KEY_COST_READ), InstitutionWorkGroup.LLM, listed(), 11));

        assertThat(InstitutionQuerySourceConstraints.vmAllocationSources(vm).sources()).isEmpty();
        assertThat(InstitutionQuerySourceConstraints.llmObservationSources(llm).sources()).isEmpty();
        assertThat(InstitutionQuerySourceConstraints.llmAggregateMoneySources(llm).sources()).isEmpty();
        assertThat(InstitutionQuerySourceConstraints.llmServiceReferenceSources(llm, facts(Set.of(FIRST_ORG))).sources()).isEmpty();
    }

    @Test
    void llmObservationUsesItsOwnGroupScopeAndDoesNotIncludeGlobalSupportWork() {
        InstitutionAssignment mixed = InstitutionAssignment.selected(ACCOUNT, FIRST_ORG,
                Set.of(InstitutionWork.VM_OBSERVE, InstitutionWork.LLM_OBSERVE),
                Map.of(InstitutionWorkGroup.VM, listed(FIRST_WORKSPACE),
                        InstitutionWorkGroup.LLM, listed(SECOND_WORKSPACE)), 11);
        InstitutionAuthority authority = authority(mixed, selected(SECOND_ORG,
                Set.of(InstitutionWork.LLM_OBSERVE_SUPPORT_READ), InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.all(), 13));
        List<InstitutionQuerySourceConstraint> sources = InstitutionQuerySourceConstraints.llmObservationSources(authority).sources();

        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst().requiredInstitutionId()).isEqualTo(FIRST_ORG);
        assertThat(sources.getFirst().group()).isEqualTo(InstitutionWorkGroup.LLM);
        assertThat(sources.getFirst().workspaceScope()).isEqualTo(listed(SECOND_WORKSPACE));
    }

    @Test
    void aggregateMoneyRequiresObservationAndCostInTheSameAssignment() {
        InstitutionAssignment observe = selected(FIRST_ORG, Set.of(InstitutionWork.LLM_OBSERVE),
                InstitutionWorkGroup.LLM, listed(FIRST_WORKSPACE), 11);
        InstitutionAssignment cost = selected(SECOND_ORG, Set.of(InstitutionWork.LLM_KEY_COST_READ),
                InstitutionWorkGroup.LLM, listed(SECOND_WORKSPACE), 13);
        InstitutionAssignment both = selected(THIRD_ORG, Set.of(InstitutionWork.LLM_OBSERVE, InstitutionWork.LLM_KEY_COST_READ),
                InstitutionWorkGroup.LLM, listed(THIRD_WORKSPACE), 17);

        assertThat(InstitutionQuerySourceConstraints.llmAggregateMoneySources(authority(observe, cost)).sources()).isEmpty();
        assertThat(InstitutionQuerySourceConstraints.llmAggregateMoneySources(authority(observe, cost, both)).sources())
                .containsExactly(new InstitutionQuerySourceConstraint(ACCOUNT, THIRD_ORG, InstitutionAssignment.Mode.SELECTED, 17,
                        InstitutionWorkGroup.LLM, listed(THIRD_WORKSPACE), listed(THIRD_WORKSPACE)));
    }

    @Test
    void aggregateMoneyDoesNotBorrowTheForeignFullKeyCostException() {
        InstitutionAuthority authority = authority(InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 11),
                selected(SECOND_ORG, Set.of(InstitutionWork.LLM_OBSERVE),
                        InstitutionWorkGroup.LLM, listed(SECOND_WORKSPACE), 13));

        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ,
                InstitutionWorkTarget.resource(SECOND_ORG, InstitutionWorkGroup.LLM, SECOND_WORKSPACE)).allowed()).isTrue();
        assertThat(InstitutionQuerySourceConstraints.llmObservationSources(authority).sources()).hasSize(2);
        assertThat(InstitutionQuerySourceConstraints.llmAggregateMoneySources(authority).sources())
                .extracting(InstitutionQuerySourceConstraint::requiredInstitutionId).containsExactly(FIRST_ORG);
    }

    @Test
    void allServiceReferencesRequireAnExistingInstitutionButNoResourceRows() {
        InstitutionAuthority authority = authority(InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 11),
                selected(SECOND_ORG, Set.of(InstitutionWork.LLM_OBSERVE),
                        InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.all(), 13));

        assertThat(InstitutionQuerySourceConstraints.llmServiceReferenceSources(authority, facts(Set.of())).sources()).isEmpty();
        assertThat(InstitutionQuerySourceConstraints.llmServiceReferenceSources(authority, facts(Set.of(FIRST_ORG))).sources())
                .extracting(InstitutionQuerySourceConstraint::requiredInstitutionId).containsExactly(FIRST_ORG);
        assertThat(InstitutionQuerySourceConstraints.llmServiceReferenceSources(authority, facts(Set.of(FIRST_ORG, SECOND_ORG))).sources())
                .allSatisfy(source -> assertThat(source.workspaceScope()).isEqualTo(InstitutionWorkspaceScope.all())).hasSize(2);
    }

    @Test
    void listedServiceReferencesRequireVerifiedLlmRelationsInTheSameInstitutionAndWorkspace() {
        InstitutionAuthority authority = authority(selected(FIRST_ORG, Set.of(InstitutionWork.LLM_OBSERVE),
                InstitutionWorkGroup.LLM, listed(FIRST_WORKSPACE, SECOND_WORKSPACE), 11));

        assertThat(InstitutionQuerySourceConstraints.llmServiceReferenceSources(authority, facts(Set.of(FIRST_ORG))).sources()).isEmpty();
        assertThat(InstitutionQuerySourceConstraints.llmServiceReferenceSources(authority,
                facts(Set.of(FIRST_ORG, SECOND_ORG), relation(InstitutionLlmServiceRelationKind.LLM_KEY, 20, SECOND_ORG, FIRST_WORKSPACE),
                        relation(InstitutionLlmServiceRelationKind.LLM_REQUEST, 21, FIRST_ORG, THIRD_WORKSPACE))).sources()).isEmpty();
        assertThat(InstitutionQuerySourceConstraints.llmServiceReferenceSources(authority,
                facts(Set.of(FIRST_ORG), relation(InstitutionLlmServiceRelationKind.LLM_KEY, 20, FIRST_ORG, FIRST_WORKSPACE),
                        relation(InstitutionLlmServiceRelationKind.LLM_REQUEST, 21, FIRST_ORG, SECOND_WORKSPACE))).sources())
                .extracting(InstitutionQuerySourceConstraint::workspaceScope).containsExactly(listed(FIRST_WORKSPACE, SECOND_WORKSPACE));
    }

    @Test
    void listedServiceReferencesNarrowQueryScopeWithoutLosingAssignedScope() {
        InstitutionAuthority authority = authority(selected(FIRST_ORG, Set.of(InstitutionWork.LLM_OBSERVE),
                InstitutionWorkGroup.LLM, listed(FIRST_WORKSPACE, SECOND_WORKSPACE), 11));
        InstitutionQuerySourceConstraints constraints = InstitutionQuerySourceConstraints.llmServiceReferenceSources(authority,
                facts(Set.of(FIRST_ORG), relation(InstitutionLlmServiceRelationKind.LLM_REQUEST, 20, FIRST_ORG, FIRST_WORKSPACE)));

        assertThat(constraints.binding()).isEqualTo(InstitutionQueryBinding.LLM_SERVICE_REFERENCE);
        assertThat(constraints.sources()).hasSize(1);
        assertThat(constraints.sources().getFirst().assignedWorkspaceScope()).isEqualTo(listed(FIRST_WORKSPACE, SECOND_WORKSPACE));
        assertThat(constraints.sources().getFirst().workspaceScope()).isEqualTo(listed(FIRST_WORKSPACE));
    }

    @Test
    void serviceReferenceCannotBorrowAnotherSourcesWorkOrRelations() {
        InstitutionAuthority authority = authority(
                selected(FIRST_ORG, Set.of(InstitutionWork.LLM_OBSERVE), InstitutionWorkGroup.LLM, listed(SECOND_WORKSPACE), 11),
                selected(SECOND_ORG, Set.of(InstitutionWork.LLM_METADATA_SUPPORT_READ), InstitutionWorkGroup.LLM, listed(SECOND_WORKSPACE), 13));

        assertThat(InstitutionQuerySourceConstraints.llmServiceReferenceSources(authority,
                facts(Set.of(FIRST_ORG, SECOND_ORG), relation(InstitutionLlmServiceRelationKind.LLM_KEY, 20, SECOND_ORG, SECOND_WORKSPACE)))
                .sources()).isEmpty();
    }

    @Test
    void sequentialRevocationRemovesOnlyItsOwnCompleteSource() {
        InstitutionAssignment first = selected(FIRST_ORG, Set.of(InstitutionWork.VM_OBSERVE),
                InstitutionWorkGroup.VM, InstitutionWorkspaceScope.all(), 11);
        InstitutionAssignment second = selected(SECOND_ORG, Set.of(InstitutionWork.VM_OBSERVE),
                InstitutionWorkGroup.VM, listed(SECOND_WORKSPACE), 13);

        assertThat(InstitutionQuerySourceConstraints.vmAllocationSources(authority(first, second)).sources()).hasSize(2);
        assertThat(InstitutionQuerySourceConstraints.vmAllocationSources(authority(second)).sources())
                .containsExactly(new InstitutionQuerySourceConstraint(ACCOUNT, SECOND_ORG, InstitutionAssignment.Mode.SELECTED, 13,
                        InstitutionWorkGroup.VM, listed(SECOND_WORKSPACE), listed(SECOND_WORKSPACE)));
        assertThat(InstitutionQuerySourceConstraints.vmAllocationSources(authority()).sources()).isEmpty();
    }

    @Test
    void newSupportObservationWorkKeepsMetadataAndActualInstitutionOperationsSeparate() {
        InstitutionAuthority observe = authority(selected(FIRST_ORG, Set.of(InstitutionWork.LLM_OBSERVE_SUPPORT_READ),
                InstitutionWorkGroup.LLM, listed(FIRST_WORKSPACE), 11));
        InstitutionAuthority metadata = authority(selected(FIRST_ORG, Set.of(InstitutionWork.LLM_METADATA_SUPPORT_READ),
                InstitutionWorkGroup.LLM, listed(FIRST_WORKSPACE), 11));
        InstitutionWorkTarget foreign = InstitutionWorkTarget.resource(SECOND_ORG, InstitutionWorkGroup.LLM, FIRST_WORKSPACE);

        assertThat(observe.evaluate(InstitutionWork.LLM_OBSERVE_SUPPORT_READ, foreign).allowed()).isTrue();
        assertThat(observe.evaluate(InstitutionWork.LLM_METADATA_SUPPORT_READ, foreign).allowed()).isFalse();
        assertThat(metadata.evaluate(InstitutionWork.LLM_OBSERVE_SUPPORT_READ, foreign).allowed()).isFalse();
        assertThat(observe.evaluate(InstitutionWork.LLM_KEY_COST_READ, foreign).allowed()).isFalse();
        assertThat(observe.evaluate(InstitutionWork.LLM_OBSERVE_SUPPORT_READ,
                InstitutionWorkTarget.resource(SECOND_ORG, InstitutionWorkGroup.LLM, SECOND_WORKSPACE)).allowed()).isFalse();
        assertThat(observe.evaluate(InstitutionWork.LLM_OBSERVE_SUPPORT_READ,
                InstitutionWorkTarget.resource(SECOND_ORG, InstitutionWorkGroup.LLM, null)).allowed()).isFalse();
        assertThat(InstitutionQuerySourceConstraints.llmObservationSources(observe).sources()).isEmpty();
    }

    @Test
    void catalogueOnlyReadNeedsNoScopeAndDoesNotGrantKeyFinanceOrObservations() {
        InstitutionAssignment assignment = InstitutionAssignment.selected(ACCOUNT, FIRST_ORG,
                Set.of(InstitutionWork.LLM_MODEL_CATALOGUE_READ), Map.of(), 11);
        InstitutionAuthority authority = authority(assignment);

        assertThat(authority.displayRole()).contains(UserRole.ORG_VIEWER);
        assertThat(authority.evaluate(InstitutionWork.LLM_MODEL_CATALOGUE_READ, InstitutionWorkTarget.global()).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ,
                InstitutionWorkTarget.resource(SECOND_ORG, InstitutionWorkGroup.LLM, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(InstitutionQuerySourceConstraints.llmObservationSources(authority).sources()).isEmpty();
        for (InstitutionWork work : List.of(InstitutionWork.LLM_MODEL_CATALOGUE_READ,
                InstitutionWork.LLM_OBSERVE_SUPPORT_READ, InstitutionWork.GPU_OBSERVE_SUPPORT_READ)) {
            assertThat(work.write()).isFalse();
            assertThat(work.selectable()).isTrue();
            assertThat(InstitutionWorkCatalogue.includedInFull(work)).isTrue();
        }
    }

    @Test
    void newReadWorkRoundTripsThroughTheExistingCodecShape() {
        InstitutionAssignment selected = InstitutionAssignment.selected(ACCOUNT, FIRST_ORG,
                Set.of(InstitutionWork.LLM_MODEL_CATALOGUE_READ, InstitutionWork.LLM_OBSERVE_SUPPORT_READ, InstitutionWork.GPU_OBSERVE_SUPPORT_READ),
                Map.of(InstitutionWorkGroup.LLM, listed(FIRST_WORKSPACE), InstitutionWorkGroup.GPU, InstitutionWorkspaceScope.all()), 11);
        InstitutionAssignmentCodec codec = new InstitutionAssignmentCodec();
        for (InstitutionAssignment assignment : List.of(selected, InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 13))) {
            InstitutionAssignmentCodec.Encoded encoded = codec.encode(assignment);
            assertThat(codec.decode(ACCOUNT, FIRST_ORG, assignment.displayRole(), encoded.mode(), encoded.payload(), assignment.revision()))
                    .isEqualTo(assignment);
            assertThat(encoded.payload()).doesNotContain("binding", "referenceFacts", "sourceConstraints");
            if (assignment.mode() == InstitutionAssignment.Mode.FULL) {
                assertThat(encoded.payload()).isEqualTo("{\"work\":[],\"scopes\":{}}");
            }
        }
    }

    @Test
    void constraintsAndResolverFactsAreImmutableSnapshots() {
        Set<UUID> institutions = new HashSet<>(Set.of(FIRST_ORG));
        Set<InstitutionLlmServiceRelation> relations = new HashSet<>(Set.of(
                relation(InstitutionLlmServiceRelationKind.LLM_KEY, 20, FIRST_ORG, FIRST_WORKSPACE)));
        InstitutionLlmServiceReferenceFacts facts = new InstitutionLlmServiceReferenceFacts(institutions, relations);
        List<InstitutionAssignment> assignments = new ArrayList<>(List.of(selected(FIRST_ORG,
                Set.of(InstitutionWork.LLM_OBSERVE), InstitutionWorkGroup.LLM, listed(FIRST_WORKSPACE, SECOND_WORKSPACE), 11)));
        InstitutionQuerySourceConstraints constraints = InstitutionQuerySourceConstraints.llmServiceReferenceSources(
                new InstitutionAuthority(ACCOUNT, assignments), facts);
        institutions.clear(); relations.clear(); assignments.clear();

        assertThat(constraints.sources()).hasSize(1);
        assertThat(constraints.sources().getFirst().workspaceScope()).isEqualTo(listed(FIRST_WORKSPACE));
        assertThatThrownBy(() -> constraints.sources().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> constraints.sources().getFirst().workspaceScope().workspaceIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> facts.existingInstitutionIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> facts.liveLlmRelations().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void contradictoryOrIncompleteResolverFactsFailClosed() {
        InstitutionLlmServiceRelation key = relation(InstitutionLlmServiceRelationKind.LLM_KEY, 20, FIRST_ORG, FIRST_WORKSPACE);

        assertThatThrownBy(() -> facts(Set.of(), key)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> facts(Set.of(FIRST_ORG), key,
                relation(InstitutionLlmServiceRelationKind.LLM_KEY, 20, FIRST_ORG, SECOND_WORKSPACE))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstitutionLlmServiceRelation(null, id(20), FIRST_ORG, FIRST_WORKSPACE)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new InstitutionLlmServiceRelation(InstitutionLlmServiceRelationKind.LLM_KEY, id(20), FIRST_ORG, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> InstitutionQuerySourceConstraints.llmServiceReferenceSources(authority(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void fixedBindingMeaningsChangeTheFingerprintWithoutChangingGrantRevision() {
        InstitutionAssignment full = InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 11);
        String descriptor = InstitutionWorkCatalogue.descriptor();
        String changedBinding = descriptor.replace("same-assignment-work+no-full-global-key-cost-exception",
                "different-aggregate-money-meaning");

        assertThat(descriptor.lines().filter(line -> line.startsWith("binding:"))).hasSize(4);
        assertThat(authority(full).fingerprint())
                .isNotEqualTo(new InstitutionAuthority(ACCOUNT, List.of(full), changedBinding).fingerprint());
        assertThat(full.revision()).isEqualTo(11);
    }

    @Test
    void collectionConstraintsDoNotRelaxScalarResourceTargets() {
        InstitutionAuthority authority = authority(selected(FIRST_ORG, Set.of(InstitutionWork.VM_OBSERVE),
                InstitutionWorkGroup.VM, InstitutionWorkspaceScope.all(), 11));

        assertThat(InstitutionQuerySourceConstraints.vmAllocationSources(authority).sources()).hasSize(1);
        assertThat(authority.evaluate(InstitutionWork.VM_OBSERVE,
                InstitutionWorkTarget.resource(FIRST_ORG, InstitutionWorkGroup.VM, null)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.VM_OBSERVE,
                InstitutionWorkTarget.resource(null, InstitutionWorkGroup.VM, FIRST_WORKSPACE)).allowed()).isFalse();
    }

    @Test
    void publicQueryApiHasOnlyFourFixedFactoriesAndNoAdmissionMethod() {
        Set<String> factories = Arrays.stream(InstitutionQuerySourceConstraints.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()))
                .map(method -> method.getName()).collect(Collectors.toSet());

        assertThat(factories).containsExactlyInAnyOrder("vmAllocationSources", "llmObservationSources",
                "llmServiceReferenceSources", "llmAggregateMoneySources");
        assertThat(InstitutionQuerySourceConstraints.class.getConstructors()).isEmpty();
        assertThat(Arrays.stream(InstitutionQuerySourceConstraints.class.getMethods()).map(method -> method.getName()))
                .doesNotContain("allowed", "evaluate", "sql");
        assertThat(InstitutionQueryBinding.values()).hasSize(4);
    }

    private static InstitutionAssignment selected(UUID institution, Set<InstitutionWork> work,
            InstitutionWorkGroup group, InstitutionWorkspaceScope scope, long revision) {
        return InstitutionAssignment.selected(ACCOUNT, institution, work, Map.of(group, scope), revision);
    }

    private static InstitutionAuthority authority(InstitutionAssignment... assignments) {
        return new InstitutionAuthority(ACCOUNT, Arrays.asList(assignments));
    }

    private static InstitutionWorkspaceScope listed(UUID... workspaces) {
        return InstitutionWorkspaceScope.listed(Arrays.asList(workspaces));
    }

    private static InstitutionLlmServiceReferenceFacts facts(Set<UUID> institutions, InstitutionLlmServiceRelation... relations) {
        return new InstitutionLlmServiceReferenceFacts(institutions, Set.copyOf(Arrays.asList(relations)));
    }

    private static InstitutionLlmServiceRelation relation(InstitutionLlmServiceRelationKind kind, long resourceId,
            UUID institution, UUID workspace) {
        return new InstitutionLlmServiceRelation(kind, id(resourceId), institution, workspace);
    }

    private static UUID id(long value) { return new UUID(0, value); }
}
