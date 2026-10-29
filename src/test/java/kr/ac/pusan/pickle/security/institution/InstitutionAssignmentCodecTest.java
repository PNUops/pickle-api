package kr.ac.pusan.pickle.security.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.Test;

class InstitutionAssignmentCodecTest {
    private static final UUID ACCOUNT = new UUID(10, 20);
    private static final UUID ORG = new UUID(30, 40);
    private static final UUID WORKSPACE = new UUID(50, 60);
    private final InstitutionAssignmentCodec codec = new InstitutionAssignmentCodec();

    @Test
    void fullAndSelectedRoundTripWithoutLosingPublicIdsOrEmptyListedScope() {
        List<InstitutionAssignment> assignments = List.of(InstitutionAssignment.full(ACCOUNT, ORG, 19),
                InstitutionAssignment.selected(ACCOUNT, ORG,
                        Set.of(InstitutionWork.VM_POWER, InstitutionWork.LLM_OBSERVE, InstitutionWork.NOTICE_MANAGE),
                        Map.of(InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(Set.of(WORKSPACE)),
                                InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.listed(Set.of())), 23));
        for (InstitutionAssignment original : assignments) {
            InstitutionAssignmentCodec.Encoded encoded = codec.encode(original);
            assertThat(codec.decode(ACCOUNT, ORG, original.displayRole(), encoded.mode(), encoded.payload(), original.revision()))
                    .isEqualTo(original);
        }
    }

    @Test
    void allAndListedRemainDistinctAndEncodingIsStableAcrossInputOrder() {
        UUID second = new UUID(70, 80);
        InstitutionAssignment original = InstitutionAssignment.selected(ACCOUNT, ORG,
                Set.of(InstitutionWork.VM_OBSERVE, InstitutionWork.VM_POWER, InstitutionWork.LLM_OBSERVE),
                Map.of(InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(List.of(second, WORKSPACE)),
                        InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.all()), 1);
        InstitutionAssignment reordered = InstitutionAssignment.selected(ACCOUNT, ORG,
                Set.of(InstitutionWork.LLM_OBSERVE, InstitutionWork.VM_POWER, InstitutionWork.VM_OBSERVE),
                Map.of(InstitutionWorkGroup.LLM, InstitutionWorkspaceScope.all(),
                        InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(List.of(WORKSPACE, second))), 1);

        assertThat(codec.encode(original)).isEqualTo(codec.encode(reordered));
        assertThat(codec.encode(original).payload()).contains("\"mode\":\"ALL\"", "\"mode\":\"LISTED\"");
    }

    @Test
    void completelyAbsentStorageIsUnconvertedRatherThanAnImplicitFullGrant() {
        assertReason(() -> codec.decode(ACCOUNT, ORG, UserRole.ORG_ADMIN, null, null, null),
                InstitutionAssignmentStorageException.Reason.UNCONVERTED);
    }

    @Test
    void partialStorageAndNonpositiveRevisionsAreRejected() {
        assertReason(() -> codec.decode(ACCOUNT, ORG, UserRole.ORG_ADMIN, "FULL", null, 1L),
                InstitutionAssignmentStorageException.Reason.CORRUPT_PAYLOAD);
        assertReason(() -> codec.decode(ACCOUNT, ORG, UserRole.ORG_ADMIN, null, "{}", 1L),
                InstitutionAssignmentStorageException.Reason.CORRUPT_PAYLOAD);
        for (Long revision : new Long[] {null, 0L, -1L}) {
            assertReason(() -> codec.decode(ACCOUNT, ORG, UserRole.ORG_ADMIN, "FULL", "{\"work\":[],\"scopes\":{}}", revision),
                    InstitutionAssignmentStorageException.Reason.CORRUPT_PAYLOAD);
        }
    }

    @Test
    void malformedUnknownAndPartiallyStructuredPayloadsAreRejectedAsAWhole() {
        List<String> payloads = List.of("not-json", "null", "[]", "{}", "{\"work\":[]}",
                "{\"work\":[],\"scopes\":{},\"future\":true}",
                "{\"work\":[],\"work\":[],\"scopes\":{}}",
                "{\"work\":[],\"scopes\":{}} {}",
                "{\"work\":[\"future.work\"],\"scopes\":{}}",
                "{\"work\":[1],\"scopes\":{}}",
                "{\"work\":[\"notice.manage\",\"notice.manage\"],\"scopes\":{}}",
                "{\"work\":[\"vm.power\"],\"scopes\":{}}",
                "{\"work\":[\"notice.manage\"],\"scopes\":{\"UNKNOWN\":{\"mode\":\"ALL\",\"workspaceIds\":[]}}}",
                scoped("{\"mode\":\"ALL\"}"), scoped("{\"mode\":\"ALL\",\"workspaceIds\":null}"),
                scoped("{\"mode\":\"ALL\",\"workspaceIds\":[],\"future\":true}"),
                scoped("{\"mode\":\"UNKNOWN\",\"workspaceIds\":[]}"),
                scoped("{\"mode\":\"ALL\",\"workspaceIds\":[\"" + WORKSPACE + "\"]}"),
                scoped("{\"mode\":\"LISTED\",\"workspaceIds\":[\"1-1-1-1-1\"]}"),
                scoped("{\"mode\":\"LISTED\",\"workspaceIds\":[\"not-a-uuid\"]}"),
                scoped("{\"mode\":\"LISTED\",\"workspaceIds\":[1]}"),
                scoped("{\"mode\":\"LISTED\",\"workspaceIds\":[\"" + WORKSPACE + "\",\"" + WORKSPACE + "\"]}"));
        for (String payload : payloads) {
            assertThatThrownBy(() -> codec.decode(ACCOUNT, ORG, UserRole.ORG_MANAGER, "SELECTED", payload, 1L))
                    .as("invalid payload %s", payload).isInstanceOfSatisfying(InstitutionAssignmentStorageException.class,
                            exception -> assertThat(exception.reason()).isEqualTo(InstitutionAssignmentStorageException.Reason.CORRUPT_PAYLOAD));
        }
    }

    @Test
    void modeAndDisplayRoleContradictionsCannotFallBackToTheStoredRole() {
        assertReason(() -> codec.decode(ACCOUNT, ORG, UserRole.ORG_ADMIN, "UNKNOWN", "{\"work\":[],\"scopes\":{}}", 1L),
                InstitutionAssignmentStorageException.Reason.CORRUPT_PAYLOAD);
        assertReason(() -> codec.decode(ACCOUNT, ORG, UserRole.ORG_MANAGER, "FULL", "{\"work\":[],\"scopes\":{}}", 1L),
                InstitutionAssignmentStorageException.Reason.ROLE_MISMATCH);
        assertReason(() -> codec.decode(ACCOUNT, ORG, UserRole.ORG_VIEWER, "SELECTED",
                "{\"work\":[\"notice.manage\"],\"scopes\":{}}", 1L), InstitutionAssignmentStorageException.Reason.ROLE_MISMATCH);
        assertReason(() -> codec.decode(ACCOUNT, ORG, UserRole.ORG_ADMIN, "FULL",
                "{\"work\":[\"notice.manage\"],\"scopes\":{}}", 1L), InstitutionAssignmentStorageException.Reason.CORRUPT_PAYLOAD);
    }

    @Test
    void delegationAndSelectedEmptyWorkAreNotValidStoredSelections() {
        for (String work : List.of("[]", "[\"institution.delegate\"]")) {
            assertReason(() -> codec.decode(ACCOUNT, ORG, UserRole.ORG_MANAGER, "SELECTED",
                    "{\"work\":" + work + ",\"scopes\":{}}", 1L), InstitutionAssignmentStorageException.Reason.CORRUPT_PAYLOAD);
        }
    }

    private static String scoped(String scope) {
        return "{\"work\":[\"vm.power\"],\"scopes\":{\"VM\":" + scope + "}}";
    }

    private static void assertReason(Runnable action, InstitutionAssignmentStorageException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(InstitutionAssignmentStorageException.class,
                exception -> assertThat(exception.reason()).isEqualTo(reason));
    }
}
