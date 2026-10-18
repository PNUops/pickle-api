package kr.ac.pusan.pickle.security.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.Test;

class InstitutionAuthorityTest {
    private static final UUID ACCOUNT = id(1);
    private static final UUID FIRST_ORG = id(2);
    private static final UUID SECOND_ORG = id(3);
    private static final UUID FIRST_WORKSPACE = id(4);
    private static final UUID SECOND_WORKSPACE = id(5);

    @Test
    void directOperationsRequireTheActualInstitutionEvenInTheSameWorkspace() {
        InstitutionAuthority authority = authority(selected(FIRST_ORG, InstitutionWork.VM_POWER,
                InstitutionWorkspaceScope.all()));

        assertThat(authority.evaluate(InstitutionWork.VM_POWER, vm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.VM_POWER, vm(SECOND_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
    }

    @Test
    void explicitSupportPreservesEachForeignInstitutionSourceThroughSequentialRevocation() {
        InstitutionAssignment first = selected(FIRST_ORG, InstitutionWork.VM_ACL_SUPPORT_MANAGE,
                InstitutionWorkspaceScope.all());
        InstitutionAssignment second = selected(SECOND_ORG, InstitutionWork.VM_ACL_SUPPORT_MANAGE,
                InstitutionWorkspaceScope.all());
        InstitutionWorkTarget target = vm(id(99), FIRST_WORKSPACE);

        assertThat(authority(first, second).evaluate(InstitutionWork.VM_ACL_SUPPORT_MANAGE, target).sources())
                .extracting(InstitutionWorkDecision.GrantSource::institutionId).containsExactly(FIRST_ORG, SECOND_ORG);
        assertThat(authority(second).evaluate(InstitutionWork.VM_ACL_SUPPORT_MANAGE, target).sources())
                .extracting(InstitutionWorkDecision.GrantSource::institutionId).containsExactly(SECOND_ORG);
        assertThat(authority().evaluate(InstitutionWork.VM_ACL_SUPPORT_MANAGE, target).allowed()).isFalse();
    }

    @Test
    void workFromOneSourceCannotBorrowAnotherSourcesWorkspaceScope() {
        InstitutionAuthority authority = authority(
                selected(FIRST_ORG, InstitutionWork.VM_ACL_SUPPORT_MANAGE, listed(FIRST_WORKSPACE)),
                selected(SECOND_ORG, InstitutionWork.VM_METADATA_SUPPORT_READ, listed(SECOND_WORKSPACE)));

        assertThat(authority.evaluate(InstitutionWork.VM_ACL_SUPPORT_MANAGE, vm(id(99), FIRST_WORKSPACE)).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.VM_ACL_SUPPORT_MANAGE, vm(id(99), SECOND_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.VM_METADATA_SUPPORT_READ, vm(id(99), SECOND_WORKSPACE)).allowed()).isTrue();
    }

    @Test
    void globalDomainMetadataSupportNeedsItsVerifiedWorkspaceWithoutInventingInstitutionOwnership() {
        InstitutionAuthority support = authority(selected(FIRST_ORG,
                InstitutionWork.DOMAIN_METADATA_SUPPORT_READ, listed(FIRST_WORKSPACE)));
        InstitutionWorkTarget knownWorkspace = InstitutionWorkTarget.resource(null,
                InstitutionWorkGroup.DOMAIN, FIRST_WORKSPACE);

        assertThat(support.evaluate(InstitutionWork.DOMAIN_METADATA_SUPPORT_READ, knownWorkspace).allowed()).isTrue();
        assertThat(authority(InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 1))
                .evaluate(InstitutionWork.DOMAIN_METADATA_SUPPORT_READ, knownWorkspace).allowed()).isTrue();
        assertThat(support.evaluate(InstitutionWork.DOMAIN_METADATA_SUPPORT_READ,
                InstitutionWorkTarget.resource(null, InstitutionWorkGroup.DOMAIN, SECOND_WORKSPACE)).allowed()).isFalse();
    }

    @Test
    void differentGroupsMayUseDifferentScopesWhileSameGroupWorkSharesOneScope() {
        InstitutionAssignment assignment = InstitutionAssignment.selected(ACCOUNT, FIRST_ORG,
                Set.of(InstitutionWork.VM_OBSERVE, InstitutionWork.VM_POWER, InstitutionWork.LLM_TOKEN_OPERATE),
                Map.of(InstitutionWorkGroup.VM, listed(FIRST_WORKSPACE),
                        InstitutionWorkGroup.LLM, listed(SECOND_WORKSPACE)), 1);
        InstitutionAuthority authority = authority(assignment);

        assertThat(authority.evaluate(InstitutionWork.VM_OBSERVE, vm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.VM_POWER, vm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.VM_OBSERVE, vm(FIRST_ORG, SECOND_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.VM_POWER, vm(FIRST_ORG, SECOND_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.LLM_TOKEN_OPERATE, llm(FIRST_ORG, SECOND_WORKSPACE)).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.LLM_TOKEN_OPERATE, llm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
    }

    @Test
    void selectingAllCurrentWorkNeverBecomesFullOrDelegation() {
        Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes = new EnumMap<>(InstitutionWorkGroup.class);
        for (InstitutionWorkGroup group : InstitutionWorkGroup.values()) { scopes.put(group, InstitutionWorkspaceScope.all()); }
        InstitutionAssignment selected = InstitutionAssignment.selected(ACCOUNT, FIRST_ORG,
                InstitutionWorkCatalogue.selectableWork(), scopes, 7);
        InstitutionAuthority authority = authority(selected);

        assertThat(selected.mode()).isEqualTo(InstitutionAssignment.Mode.SELECTED);
        assertThat(authority.displayRole()).contains(UserRole.ORG_MANAGER);
        assertThat(authority.evaluate(InstitutionWork.INSTITUTION_DELEGATE,
                InstitutionWorkTarget.institution(FIRST_ORG)).allowed()).isFalse();
        assertThat(authority(InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 7)).evaluate(
                InstitutionWork.INSTITUTION_DELEGATE, InstitutionWorkTarget.institution(FIRST_ORG)).allowed()).isTrue();
    }

    @Test
    void fullStillRequiresKnownWorkAndTheActualInstitutionForVmOperations() {
        InstitutionAuthority authority = authority(InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 1));

        assertThat(authority.displayRole()).contains(UserRole.ORG_ADMIN);
        assertThat(authority.evaluate(InstitutionWork.VM_POWER, vm(SECOND_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate("sys.vm.force-delete", vm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate("new-global-work", InstitutionWorkTarget.global()).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.VM_METADATA_SUPPORT_READ, vm(SECOND_ORG, FIRST_WORKSPACE)).allowed()).isTrue();
    }

    @Test
    void fullKeyCostReadIncludesKeysOwnedByAnotherInstitution() {
        InstitutionAssignment full = InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 7);
        InstitutionWorkDecision decision = authority(full).evaluate(InstitutionWork.LLM_KEY_COST_READ,
                llm(SECOND_ORG, SECOND_WORKSPACE));

        assertThat(decision.sources()).containsExactly(new InstitutionWorkDecision.GrantSource(
                ACCOUNT, FIRST_ORG, InstitutionAssignment.Mode.FULL, 7));
    }

    @Test
    void fullKeyCostReadNeedsVerifiedKindAndWorkspaceRatherThanInstitutionOwnership() {
        InstitutionAuthority authority = authority(InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 7));

        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ,
                InstitutionWorkTarget.resource(null, InstitutionWorkGroup.LLM, FIRST_WORKSPACE)).allowed()).isTrue();
    }

    @Test
    void fullKeyCostReadRejectsUnknownKindOrWorkspace() {
        InstitutionAuthority authority = authority(InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 7));
        for (InstitutionWorkTarget target : List.of(
                InstitutionWorkTarget.resource(SECOND_ORG, null, FIRST_WORKSPACE),
                InstitutionWorkTarget.resource(null, null, FIRST_WORKSPACE),
                InstitutionWorkTarget.resource(SECOND_ORG, InstitutionWorkGroup.VM, FIRST_WORKSPACE),
                InstitutionWorkTarget.resource(SECOND_ORG, InstitutionWorkGroup.LLM, null),
                InstitutionWorkTarget.resource(null, InstitutionWorkGroup.LLM, null),
                InstitutionWorkTarget.global(), InstitutionWorkTarget.institution(SECOND_ORG))) {
            assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ, target).allowed()).as("target %s", target).isFalse();
        }
    }

    @Test
    void selectedKeyCostReadCannotBorrowAnotherSourcesWorkOrWorkspaceScope() {
        InstitutionAuthority authority = authority(
                selected(FIRST_ORG, InstitutionWork.LLM_KEY_COST_READ, listed(FIRST_WORKSPACE)),
                selected(SECOND_ORG, InstitutionWork.LLM_STATE_OPERATE, listed(SECOND_WORKSPACE)));

        assertThat(authority.displayRole()).contains(UserRole.ORG_MANAGER);
        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ, llm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ, llm(FIRST_ORG, SECOND_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ, llm(SECOND_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ, llm(SECOND_ORG, SECOND_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ,
                InstitutionWorkTarget.resource(null, InstitutionWorkGroup.LLM, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(authority(selected(FIRST_ORG, InstitutionWork.LLM_KEY_COST_READ, listed()))
                .evaluate(InstitutionWork.LLM_KEY_COST_READ, llm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
    }

    @Test
    void selectedKeyCostReadWithAllWorkspacesStillRequiresItsActualInstitution() {
        InstitutionAuthority authority = authority(selected(FIRST_ORG, InstitutionWork.LLM_KEY_COST_READ,
                InstitutionWorkspaceScope.all()));

        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ, llm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ, llm(FIRST_ORG, SECOND_WORKSPACE)).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ, llm(SECOND_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ,
                InstitutionWorkTarget.resource(FIRST_ORG, InstitutionWorkGroup.LLM, null)).allowed()).isFalse();
    }

    @Test
    void globalKeyCostReadKeepsOtherFullSourcesAfterDowngradeUntilTheLastIsRevoked() {
        InstitutionAssignment first = InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 7);
        InstitutionAssignment second = InstitutionAssignment.full(ACCOUNT, SECOND_ORG, 11);
        Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes = new EnumMap<>(InstitutionWorkGroup.class);
        for (InstitutionWorkGroup group : InstitutionWorkGroup.values()) { scopes.put(group, InstitutionWorkspaceScope.all()); }
        InstitutionAssignment downgraded = InstitutionAssignment.selected(ACCOUNT, FIRST_ORG,
                InstitutionWorkCatalogue.selectableWork(), scopes, 8);
        InstitutionWorkTarget foreignKey = llm(id(99), FIRST_WORKSPACE);

        assertThat(authority(first, second).evaluate(InstitutionWork.LLM_KEY_COST_READ, foreignKey).sources())
                .containsExactly(new InstitutionWorkDecision.GrantSource(ACCOUNT, FIRST_ORG, InstitutionAssignment.Mode.FULL, 7),
                        new InstitutionWorkDecision.GrantSource(ACCOUNT, SECOND_ORG, InstitutionAssignment.Mode.FULL, 11));
        assertThat(authority(downgraded, second).evaluate(InstitutionWork.LLM_KEY_COST_READ, foreignKey).sources())
                .containsExactly(new InstitutionWorkDecision.GrantSource(ACCOUNT, SECOND_ORG, InstitutionAssignment.Mode.FULL, 11));
        assertThat(authority(first).evaluate(InstitutionWork.LLM_KEY_COST_READ, foreignKey).sources())
                .containsExactly(new InstitutionWorkDecision.GrantSource(ACCOUNT, FIRST_ORG, InstitutionAssignment.Mode.FULL, 7));
        assertThat(downgraded.mode()).isEqualTo(InstitutionAssignment.Mode.SELECTED);
        assertThat(authority(downgraded).displayRole()).contains(UserRole.ORG_MANAGER);
        assertThat(authority(downgraded).evaluate(InstitutionWork.LLM_KEY_COST_READ, foreignKey).allowed()).isFalse();
        assertThat(authority(downgraded).evaluate(InstitutionWork.LLM_KEY_COST_READ,
                llm(FIRST_ORG, FIRST_WORKSPACE)).sources()).containsExactly(new InstitutionWorkDecision.GrantSource(
                        ACCOUNT, FIRST_ORG, InstitutionAssignment.Mode.SELECTED, 8));
        assertThat(authority().evaluate(InstitutionWork.LLM_KEY_COST_READ, foreignKey).allowed()).isFalse();
    }

    @Test
    void fullKeyCostExceptionDoesNotExpandOtherResourceOrInstitutionWork() {
        InstitutionAuthority authority = authority(InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 7));
        for (InstitutionWork work : InstitutionWork.values()) {
            if (work.boundary() == InstitutionWork.Boundary.RESOURCE && work != InstitutionWork.LLM_KEY_COST_READ) {
                assertThat(authority.evaluate(work,
                        InstitutionWorkTarget.resource(SECOND_ORG, work.group(), FIRST_WORKSPACE)).allowed())
                        .as("foreign resource work %s", work).isFalse();
                assertThat(authority.evaluate(work,
                        InstitutionWorkTarget.resource(null, work.group(), FIRST_WORKSPACE)).allowed())
                        .as("resource without institution for %s", work).isFalse();
            } else if (work.boundary() == InstitutionWork.Boundary.INSTITUTION) {
                assertThat(authority.evaluate(work, InstitutionWorkTarget.institution(SECOND_ORG)).allowed())
                        .as("foreign institution work %s", work).isFalse();
            }
        }
    }

    @Test
    void catalogueKeepsOnlyIndividualKeyCostAsAnExplicitFullGlobalResourceRead() {
        Set<String> globalResourceReads = InstitutionWorkCatalogue.descriptor().lines()
                .filter(line -> line.split(":").length == 7 && line.endsWith(":true"))
                .map(line -> line.split(":")[0]).collect(Collectors.toSet());

        assertThat(globalResourceReads).containsExactly(InstitutionWork.LLM_KEY_COST_READ.id());
        assertThat(InstitutionWorkCatalogue.REVISION).isEqualTo("2");
        assertThat(InstitutionWork.values()).hasSize(83);
        assertThat(InstitutionWork.LLM_KEY_COST_READ.boundary()).isEqualTo(InstitutionWork.Boundary.RESOURCE);
        assertThat(InstitutionWork.LLM_KEY_COST_READ.selectable()).isTrue();
        assertThat(InstitutionWork.LLM_KEY_COST_READ.write()).isFalse();
    }

    @Test
    void catalogueGlobalReadMeaningChangesFingerprintWithoutChangingTheGrantRevision() {
        InstitutionAssignment full = InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 7);
        String descriptor = InstitutionWorkCatalogue.descriptor();
        String globalCostEntry = "llm.key.cost.read:LLM:RESOURCE:false:true:true:true";
        assertThat(descriptor).contains(globalCostEntry);
        String withoutGlobalRead = descriptor.replace(globalCostEntry,
                "llm.key.cost.read:LLM:RESOURCE:false:true:true:false");

        assertThat(authority(full).fingerprint())
                .isNotEqualTo(new InstitutionAuthority(ACCOUNT, List.of(full), withoutGlobalRead).fingerprint());
        assertThat(full.revision()).isEqualTo(7);
        assertThat(authority(full).assignments()).containsExactly(full);
    }

    @Test
    void fullGlobalWorkIsAnExplicitReviewedSetRatherThanEveryGlobalEnum() {
        Set<InstitutionWork> actual = Arrays.stream(InstitutionWork.values())
                .filter(work -> work.boundary() == InstitutionWork.Boundary.GLOBAL)
                .filter(InstitutionWorkCatalogue::includedInFull).collect(Collectors.toSet());

        assertThat(actual).containsExactlyInAnyOrder(InstitutionWork.STUDENT_LOOKUP,
                InstitutionWork.STUDENT_SUPPORT_READ, InstitutionWork.STUDENT_PROFILE_CORRECT,
                InstitutionWork.NOTICE_READ, InstitutionWork.NOTICE_MANAGE,
                InstitutionWork.SHARED_CERTIFICATE_READ, InstitutionWork.ROOT_DOMAIN_CATALOGUE_READ);
    }

    @Test
    void noticeOnlyIsManagerAndAuditOnlyIsViewerWithoutUnrelatedWork() {
        InstitutionAuthority notice = authority(global(FIRST_ORG, InstitutionWork.NOTICE_MANAGE));
        InstitutionAuthority audit = authority(global(FIRST_ORG, InstitutionWork.INSTITUTION_AUDIT_READ));

        assertThat(notice.displayRole()).contains(UserRole.ORG_MANAGER);
        assertThat(notice.evaluate(InstitutionWork.NOTICE_MANAGE, InstitutionWorkTarget.global()).allowed()).isTrue();
        assertThat(notice.evaluate(InstitutionWork.VM_POWER, vm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(audit.displayRole()).contains(UserRole.ORG_VIEWER);
        assertThat(audit.evaluate(InstitutionWork.INSTITUTION_AUDIT_READ,
                InstitutionWorkTarget.institution(FIRST_ORG)).allowed()).isTrue();
        assertThat(audit.evaluate(InstitutionWork.INSTITUTION_AUDIT_READ,
                InstitutionWorkTarget.institution(SECOND_ORG)).allowed()).isFalse();
    }

    @Test
    void anEmptyInstitutionContextHasNoRoleInsteadOfReplacingPersonalOrSystemRoles() {
        assertThat(authority().displayRole()).isEmpty();
        assertThat(authority().evaluate(InstitutionWork.NOTICE_READ, InstitutionWorkTarget.global()).allowed()).isFalse();
    }

    @Test
    void globalStudentNoticeAndSharedReferencesRequireNoFictitiousWorkspace() {
        for (InstitutionWork work : List.of(InstitutionWork.STUDENT_PROFILE_CORRECT, InstitutionWork.NOTICE_READ,
                InstitutionWork.SHARED_CERTIFICATE_READ, InstitutionWork.ROOT_DOMAIN_CATALOGUE_READ)) {
            assertThat(authority(global(FIRST_ORG, work)).evaluate(work, InstitutionWorkTarget.global()).allowed()).isTrue();
        }
        assertThat(authority(global(FIRST_ORG, InstitutionWork.INSTITUTION_ACCOUNT_FINANCE_READ)).evaluate(
                InstitutionWork.INSTITUTION_ACCOUNT_FINANCE_READ, InstitutionWorkTarget.institution(FIRST_ORG)).allowed()).isTrue();
    }

    @Test
    void unresolvedRelationsRequiredByTheChosenWorkNeverAuthorizeEvenFull() {
        InstitutionAuthority authority = authority(InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 1));

        assertThat(authority.evaluate(InstitutionWork.VM_POWER,
                InstitutionWorkTarget.resource(null, InstitutionWorkGroup.VM, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.VM_POWER,
                InstitutionWorkTarget.resource(FIRST_ORG, InstitutionWorkGroup.VM, null)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.DOMAIN_METADATA_SUPPORT_READ,
                InstitutionWorkTarget.resource(null, InstitutionWorkGroup.DOMAIN, null)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.INSTITUTION_AUDIT_READ,
                InstitutionWorkTarget.institution(null)).allowed()).isFalse();
    }

    @Test
    void emptyListedScopesAndNewWorkspaceIdsDoNotFallBackToAll() {
        InstitutionAuthority empty = authority(selected(FIRST_ORG, InstitutionWork.VM_POWER, listed()));
        InstitutionAuthority listed = authority(selected(FIRST_ORG, InstitutionWork.VM_POWER, listed(FIRST_WORKSPACE)));

        assertThat(empty.evaluate(InstitutionWork.VM_POWER, vm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(listed.evaluate(InstitutionWork.VM_POWER, vm(FIRST_ORG, SECOND_WORKSPACE)).allowed()).isFalse();
        assertThat(authority(selected(FIRST_ORG, InstitutionWork.VM_POWER, InstitutionWorkspaceScope.all()))
                .evaluate(InstitutionWork.VM_POWER, vm(FIRST_ORG, id(1000))).allowed()).isTrue();
    }

    @Test
    void permissionValuesDoNotGuessWhetherAStableWorkspaceIdWasDeleted() {
        UUID oldWorkspaceId = id(40);
        InstitutionWorkspaceScope scope = listed(oldWorkspaceId);

        assertThat(scope.workspaceIds()).containsExactly(oldWorkspaceId);
        assertThat(scope.includes(oldWorkspaceId)).isTrue();
        assertThat(scope.includes(id(41))).isFalse();
        // A resolver must reject a deleted target before calling this pure identity evaluator.
        assertThat(scope.includes(null)).isFalse();
    }

    @Test
    void wrongGroupsAndUnknownIdentifiersFailClosed() {
        InstitutionAuthority authority = authority(InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 1));

        assertThat(authority.evaluate(InstitutionWork.VM_POWER,
                InstitutionWorkTarget.resource(FIRST_ORG, InstitutionWorkGroup.LLM, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.VM_POWER,
                InstitutionWorkTarget.resource(FIRST_ORG, null, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(InstitutionWork.fromId("VM_POWER")).isEmpty();
        assertThat(InstitutionWorkGroup.fromId("UNKNOWN")).isEmpty();
        assertThat(InstitutionWork.fromId("llm.global-cost.read")).isEmpty();
    }

    @Test
    void sameActorBudgetMustMatchTheDecisionInstitutionAndWorkspace() {
        InstitutionAuthority split = authority(
                selected(FIRST_ORG, InstitutionWork.LLM_REQUEST_DECIDE, listed(FIRST_WORKSPACE)),
                selected(SECOND_ORG, InstitutionWork.LLM_KEY_BUDGET, listed(FIRST_WORKSPACE)));
        InstitutionWorkTarget target = llm(FIRST_ORG, FIRST_WORKSPACE);

        assertThat(split.evaluate(InstitutionWork.LLM_REQUEST_DECIDE, target).allowed()).isTrue();
        assertThat(split.evaluate(InstitutionWork.LLM_KEY_BUDGET, target).allowed()).isFalse();
        InstitutionAssignment complete = InstitutionAssignment.selected(ACCOUNT, FIRST_ORG,
                Set.of(InstitutionWork.LLM_REQUEST_DECIDE, InstitutionWork.LLM_KEY_BUDGET),
                Map.of(InstitutionWorkGroup.LLM, listed(FIRST_WORKSPACE)), 1);
        assertThat(authority(complete).evaluate(InstitutionWork.LLM_KEY_BUDGET, target).allowed()).isTrue();
        assertThat(authority(complete).evaluate(InstitutionWork.LLM_KEY_BUDGET,
                llm(FIRST_ORG, SECOND_WORKSPACE)).allowed()).isFalse();
    }

    @Test
    void rosterRetryAndCancellationAreIndependentWork() {
        InstitutionAuthority roster = authority(selected(FIRST_ORG, InstitutionWork.LLM_ROSTER_ISSUE,
                InstitutionWorkspaceScope.all()));

        assertThat(roster.evaluate(InstitutionWork.LLM_REQUEST_DECIDE, llm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(roster.evaluate(InstitutionWork.LLM_RECIPIENT_RETRY, llm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(roster.evaluate(InstitutionWork.WORKSPACE_RECIPIENT_CANCEL,
                InstitutionWorkTarget.workspace(FIRST_WORKSPACE)).allowed()).isFalse();
    }

    @Test
    void workspaceSupportUsesItsGrantSourceWithoutInventingAnInstitutionOwner() {
        InstitutionAuthority authority = authority(selected(FIRST_ORG, InstitutionWork.WORKSPACE_INVITATIONS_MANAGE,
                listed(FIRST_WORKSPACE)));

        assertThat(authority.evaluate(InstitutionWork.WORKSPACE_INVITATIONS_MANAGE,
                InstitutionWorkTarget.workspace(FIRST_WORKSPACE)).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.WORKSPACE_INVITATIONS_MANAGE,
                InstitutionWorkTarget.workspace(SECOND_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.WORKSPACE_INVITATIONS_MANAGE,
                InstitutionWorkTarget.resource(FIRST_ORG, InstitutionWorkGroup.WORKSPACE, FIRST_WORKSPACE)).allowed()).isFalse();
    }

    @Test
    void workspaceNotificationsNeedBothTheGroupScopeAndVerifiedAudienceQualification() {
        InstitutionAuthority authority = authority(selected(FIRST_ORG, InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND,
                listed(FIRST_WORKSPACE)));

        assertThat(authority.evaluate(InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND,
                InstitutionWorkTarget.qualifiedWorkspace(FIRST_WORKSPACE, Set.of(FIRST_ORG))).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND,
                InstitutionWorkTarget.qualifiedWorkspace(FIRST_WORKSPACE, Set.of(SECOND_ORG))).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND,
                InstitutionWorkTarget.qualifiedWorkspace(SECOND_WORKSPACE, Set.of(FIRST_ORG))).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND,
                InstitutionWorkTarget.workspace(FIRST_WORKSPACE)).allowed()).isFalse();
    }

    @Test
    void externalDomainWorkDoesNotGrantVmLinkedCustomVerification() {
        InstitutionAuthority authority = authority(selected(FIRST_ORG, InstitutionWork.DOMAIN_REQUEST_DECIDE,
                InstitutionWorkspaceScope.all()));

        assertThat(authority.evaluate(InstitutionWork.VM_CUSTOM_DOMAIN_VERIFY, vm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.DOMAIN_REQUEST_DECIDE,
                InstitutionWorkTarget.resource(FIRST_ORG, InstitutionWorkGroup.DOMAIN, FIRST_WORKSPACE)).allowed()).isTrue();
    }

    @Test
    void selectedAssignmentsCannotContainDelegationOrFakeScopesForGlobalWork() {
        assertThatThrownBy(() -> global(FIRST_ORG, InstitutionWork.INSTITUTION_DELEGATE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InstitutionAssignment.selected(ACCOUNT, FIRST_ORG, Set.of(InstitutionWork.NOTICE_MANAGE),
                Map.of(InstitutionWorkGroup.WORKSPACE, listed(FIRST_WORKSPACE)), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InstitutionAssignment.selected(ACCOUNT, FIRST_ORG, Set.of(InstitutionWork.VM_POWER), Map.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InstitutionAssignment.selected(ACCOUNT, FIRST_ORG, Set.of(), Map.of(), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fullCannotCarryAHiddenListedScopeAndInvalidRevisionIsRejected() {
        assertThatThrownBy(() -> new InstitutionAssignment(ACCOUNT, FIRST_ORG, InstitutionAssignment.Mode.FULL,
                Set.of(), Map.of(InstitutionWorkGroup.VM, listed(FIRST_WORKSPACE)), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InstitutionWorkspaceScope(InstitutionWorkspaceScope.Mode.ALL, Set.of(FIRST_WORKSPACE)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InstitutionAssignment.full(ACCOUNT, FIRST_ORG, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callersCannotMutateScopesAssignmentsSourcesOrAuthorityAfterConstruction() {
        Set<UUID> ids = new HashSet<>(Set.of(FIRST_WORKSPACE));
        InstitutionWorkspaceScope scope = InstitutionWorkspaceScope.listed(ids);
        Set<InstitutionWork> work = new HashSet<>(Set.of(InstitutionWork.VM_POWER));
        Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes = new HashMap<>(Map.of(InstitutionWorkGroup.VM, scope));
        InstitutionAssignment assignment = InstitutionAssignment.selected(ACCOUNT, FIRST_ORG, work, scopes, 1);
        List<InstitutionAssignment> assignments = new ArrayList<>(List.of(assignment));
        InstitutionAuthority authority = new InstitutionAuthority(ACCOUNT, assignments);
        ids.add(SECOND_WORKSPACE); work.add(InstitutionWork.VM_DELETION_SCHEDULE); scopes.clear(); assignments.clear();

        assertThat(authority.evaluate(InstitutionWork.VM_POWER, vm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isTrue();
        assertThat(authority.evaluate(InstitutionWork.VM_POWER, vm(FIRST_ORG, SECOND_WORKSPACE)).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.VM_DELETION_SCHEDULE, vm(FIRST_ORG, FIRST_WORKSPACE)).allowed()).isFalse();
        assertThatThrownBy(() -> authority.assignments().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> assignment.work().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> assignment.scopes().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> scope.workspaceIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> authority.evaluate(InstitutionWork.VM_POWER, vm(FIRST_ORG, FIRST_WORKSPACE)).sources().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void mixedAccountsAndDuplicateInstitutionRowsAreRejectedInsteadOfCombined() {
        InstitutionAssignment assignment = selected(FIRST_ORG, InstitutionWork.VM_POWER, listed(FIRST_WORKSPACE));

        assertThatThrownBy(() -> new InstitutionAuthority(id(90), List.of(assignment))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> authority(assignment, assignment)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identifierCatalogueIsUniqueAndHasExactlyTheFiveFixedGroups() {
        assertThat(Arrays.stream(InstitutionWork.values()).map(InstitutionWork::id).distinct().count())
                .isEqualTo(InstitutionWork.values().length);
        assertThat(InstitutionWorkGroup.values()).containsExactly(InstitutionWorkGroup.VM, InstitutionWorkGroup.LLM,
                InstitutionWorkGroup.DOMAIN, InstitutionWorkGroup.GPU, InstitutionWorkGroup.WORKSPACE);
        assertThat(InstitutionWork.fromId("vm.power")).contains(InstitutionWork.VM_POWER);
        assertThat(InstitutionWorkGroup.fromId("VM")).contains(InstitutionWorkGroup.VM);
        assertThat(Arrays.stream(InstitutionWork.values()).filter(work -> work.id().contains("acl")))
                .allMatch(work -> work.boundary() == InstitutionWork.Boundary.RESOURCE_SUPPORT);
    }

    @Test
    void fingerprintsAreIndependentOfAssignmentWorkAndWorkspaceInputOrder() {
        InstitutionAssignment first = InstitutionAssignment.selected(ACCOUNT, FIRST_ORG,
                new HashSet<>(List.of(InstitutionWork.VM_OBSERVE, InstitutionWork.VM_POWER)),
                Map.of(InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(List.of(SECOND_WORKSPACE, FIRST_WORKSPACE))), 3);
        InstitutionAssignment reordered = InstitutionAssignment.selected(ACCOUNT, FIRST_ORG,
                new HashSet<>(List.of(InstitutionWork.VM_POWER, InstitutionWork.VM_OBSERVE)),
                Map.of(InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(List.of(FIRST_WORKSPACE, SECOND_WORKSPACE))), 3);
        InstitutionAssignment second = global(SECOND_ORG, InstitutionWork.NOTICE_READ);

        assertThat(authority(first, second).fingerprint()).isEqualTo(authority(second, reordered).fingerprint()).hasSize(64);
        assertThat(authority(second, first).evaluate(InstitutionWork.NOTICE_READ, InstitutionWorkTarget.global()).sources())
                .extracting(InstitutionWorkDecision.GrantSource::institutionId).containsExactly(SECOND_ORG);
    }

    @Test
    void fingerprintsChangeWithRevisionWorkScopeSourceModeAccountAndCatalogueSemantics() {
        InstitutionAssignment base = selected(FIRST_ORG, InstitutionWork.VM_POWER, listed(FIRST_WORKSPACE));
        String fingerprint = authority(base).fingerprint();
        assertThat(authority(InstitutionAssignment.selected(ACCOUNT, FIRST_ORG, base.work(), base.scopes(), 2)).fingerprint())
                .isNotEqualTo(fingerprint);
        assertThat(authority(selected(FIRST_ORG, InstitutionWork.VM_OBSERVE, listed(FIRST_WORKSPACE))).fingerprint())
                .isNotEqualTo(fingerprint);
        assertThat(authority(selected(FIRST_ORG, InstitutionWork.VM_POWER, listed(SECOND_WORKSPACE))).fingerprint())
                .isNotEqualTo(fingerprint);
        assertThat(authority(selected(FIRST_ORG, InstitutionWork.VM_POWER, InstitutionWorkspaceScope.all())).fingerprint())
                .isNotEqualTo(fingerprint);
        assertThat(authority(selected(SECOND_ORG, InstitutionWork.VM_POWER, listed(FIRST_WORKSPACE))).fingerprint())
                .isNotEqualTo(fingerprint);
        assertThat(authority(InstitutionAssignment.full(ACCOUNT, FIRST_ORG, 1)).fingerprint()).isNotEqualTo(fingerprint);
        assertThat(new InstitutionAuthority(ACCOUNT, List.of(base), InstitutionWorkCatalogue.descriptor() + "\nchanged-work-semantics")
                .fingerprint()).isNotEqualTo(fingerprint);
        assertThat(new InstitutionAuthority(id(90), List.of()).fingerprint()).isNotEqualTo(authority().fingerprint());
    }

    private static InstitutionAuthority authority(InstitutionAssignment... assignments) {
        return new InstitutionAuthority(ACCOUNT, Arrays.asList(assignments));
    }

    private static InstitutionAssignment selected(UUID org, InstitutionWork work, InstitutionWorkspaceScope scope) {
        return InstitutionAssignment.selected(ACCOUNT, org, Set.of(work), Map.of(work.group(), scope), 1);
    }

    private static InstitutionAssignment global(UUID org, InstitutionWork work) {
        return InstitutionAssignment.selected(ACCOUNT, org, Set.of(work), Map.of(), 1);
    }

    private static InstitutionWorkspaceScope listed(UUID... ids) {
        return InstitutionWorkspaceScope.listed(Arrays.asList(ids));
    }

    private static InstitutionWorkTarget vm(UUID org, UUID workspace) {
        return InstitutionWorkTarget.resource(org, InstitutionWorkGroup.VM, workspace);
    }

    private static InstitutionWorkTarget llm(UUID org, UUID workspace) {
        return InstitutionWorkTarget.resource(org, InstitutionWorkGroup.LLM, workspace);
    }

    private static UUID id(long number) { return new UUID(0, number); }
}
