package kr.ac.pusan.pickle.recovery;

import static kr.ac.pusan.pickle.recovery.ManagedVmRecoveryGuard.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import kr.ac.pusan.pickle.vm.VmStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ManagedVmRecoveryGuardTest {

    private static final Instant NOW = Instant.parse("2026-09-16T00:10:00Z");
    private static final Instant BASELINE = NOW.minusSeconds(600);
    private static final Duration MAX_AGE = Duration.ofSeconds(30);
    private static final UUID VM = UUID.fromString("e9bc7387-f2b4-4a86-b1ab-a34daa801f04");
    private static final UUID OP = UUID.fromString("ef0ed714-408f-4b3b-abd3-7557d913ea34");
    private static final Location SOURCE = new Location(1, "source-node", 100001);
    private static final Location TARGET = new Location(2, "target-node", 900001);
    private static final String CA = "1".repeat(64);
    private static final String BACKUP_HASH = "2".repeat(64);
    private static final String BACKUP = "pbs-test:backup/vm/100001/2026-09-16T00:00:00Z";
    private static final GuestIdentity IDENTITY = new GuestIdentity(
            "restore-example", "192.0.2.25", "02:00:00:00:00:25", "3".repeat(64));

    @Test
    void preparedRestoreRequiresAnUnusedTargetAndLeavesTheLogicalIdentityIntact() {
        Fixture fixture = new Fixture();
        assertThatCode(() -> requireRestoreReady(fixture.manifest, fixture.observation(), NOW, MAX_AGE))
                .doesNotThrowAnyException();
        assertThat(fixture.database.logicalVmId()).isEqualTo(VM);
        assertThat(fixture.database.location()).isEqualTo(SOURCE);
    }

    @Test
    void verifiedRestoreProducesOnlyExpectedOldCoordinatesForTheLaterTransaction() {
        Fixture fixture = restored();
        LocationChange result = requireAdoptionReady(fixture.manifest, fixture.observation(),
                receipt(true, OP, BACKUP, TARGET, NOW.minusSeconds(2)), NOW, MAX_AGE);
        assertThat(result.logicalVmId()).isEqualTo(VM);
        assertThat(result.expectedSource()).isEqualTo(SOURCE);
        assertThat(result.expectedVmUpdatedAt()).isEqualTo(BASELINE);
        assertThat(result.target()).isEqualTo(TARGET);
        assertThat(result.ownerFencingToken()).isEqualTo(7);
        assertThat(fixture.database.location()).isEqualTo(SOURCE);
    }

    @ParameterizedTest
    @MethodSource("unsafeSources")
    void sourceMustBeStoppedAndFencedAcrossEveryRestartAndNetworkPath(ObservedGuest source) {
        Fixture fixture = new Fixture();
        fixture.guests = List.of(source);
        rejected(fixture, Rejection.SOURCE_NOT_FENCED);
    }

    static Stream<ObservedGuest> unsafeSources() {
        return Stream.of(
                guest(SOURCE, RuntimeState.RUNNING, false, false, Set.of(), false, false, null),
                guest(SOURCE, RuntimeState.UNKNOWN, false, false, Set.of(), false, false, null),
                guest(SOURCE, RuntimeState.STOPPED, true, false, Set.of(), false, false, null),
                guest(SOURCE, RuntimeState.STOPPED, false, true, Set.of(), false, false, null),
                guest(SOURCE, RuntimeState.STOPPED, false, false, Set.of("net0"), false, false, null),
                guest(SOURCE, RuntimeState.STOPPED, false, false, Set.of("net1"), false, false, null),
                guest(SOURCE, RuntimeState.STOPPED, false, false, Set.of(), true, false, null),
                guest(SOURCE, RuntimeState.STOPPED, false, false, Set.of(), false, true, null));
    }

    @Test
    void missingOrDuplicateSourceCannotBeAssumedSafe() {
        Fixture missing = new Fixture();
        missing.guests = List.of();
        rejected(missing, Rejection.SOURCE_MISSING_OR_DUPLICATED);
        Fixture duplicate = new Fixture();
        duplicate.guests = List.of(isolated(SOURCE, null),
                isolated(new Location(2, "target-node", SOURCE.vmid()), null));
        rejected(duplicate, Rejection.SOURCE_MISSING_OR_DUPLICATED);
    }

    @ParameterizedTest
    @MethodSource("nonVmKinds")
    void reusedVmidPointingAtAContainerOrTemplateCannotBecomeTheSource(GuestKind kind) {
        Fixture fixture = new Fixture();
        fixture.guests = List.of(new ObservedGuest(SOURCE, IDENTITY, kind, RuntimeState.STOPPED,
                false, false, Set.of(), false, false, null));
        rejected(fixture, Rejection.SOURCE_NOT_FENCED);
    }

    static Stream<GuestKind> nonVmKinds() {
        return Stream.of(GuestKind.LXC, GuestKind.TEMPLATE, GuestKind.UNKNOWN);
    }

    @ParameterizedTest
    @MethodSource("conflictingOwners")
    void expiredReplacedOrConcurrentOwnersCannotAdvanceRecovery(ExclusiveOwner owner) {
        Fixture fixture = new Fixture();
        fixture.owner = owner;
        rejected(fixture, Rejection.OWNER_CONFLICT);
    }

    static Stream<ExclusiveOwner> conflictingOwners() {
        return Stream.of(
                owner(OP, VM, 7, NOW, 1),
                owner(OP, VM, 8, NOW.plusSeconds(60), 1),
                owner(OP, VM, 7, NOW.plusSeconds(60), 2),
                owner(OP, VM, 7, NOW.plusSeconds(60), 0),
                owner(UUID.randomUUID(), VM, 7, NOW.plusSeconds(60), 1),
                owner(OP, UUID.randomUUID(), 7, NOW.plusSeconds(60), 1),
                new ExclusiveOwner(OP, VM, 7, NOW.plusSeconds(1), NOW.plusSeconds(60), 1));
    }

    @ParameterizedTest
    @MethodSource("activeWork")
    void anyQueuedRetryingOrParkedWorkBlocksRecovery(Activity activity) {
        Fixture fixture = new Fixture();
        fixture.activity = activity;
        rejected(fixture, Rejection.ACTIVE_WORK);
    }

    static Stream<Activity> activeWork() {
        return Stream.of(new Activity(false, 0, 0, 0, false, false),
                new Activity(true, 1, 0, 0, false, false),
                new Activity(true, 0, 1, 0, false, false),
                new Activity(true, 0, 0, 1, false, false),
                new Activity(true, 0, 0, 0, true, false),
                new Activity(true, 0, 0, 0, false, true));
    }

    @Test
    void staleOrFutureObservationsAreNotReusableProof() {
        Fixture stale = new Fixture();
        stale.observedAt = NOW.minusSeconds(31);
        rejected(stale, Rejection.STALE_OBSERVATION);
        Fixture future = new Fixture();
        future.observedAt = NOW.plusSeconds(1);
        rejected(future, Rejection.STALE_OBSERVATION);
    }

    @Test
    void clusterInventoryAndTargetReachabilityMustBeCurrentAndComplete() {
        Fixture incomplete = new Fixture();
        incomplete.complete = false;
        rejected(incomplete, Rejection.INCOMPLETE_INVENTORY);
        Fixture otherCluster = new Fixture();
        otherCluster.ca = "4".repeat(64);
        rejected(otherCluster, Rejection.WRONG_CLUSTER);
        Fixture targetOffline = new Fixture();
        targetOffline.nodes = Set.of(SOURCE.nodeId());
        rejected(targetOffline, Rejection.TARGET_NODE_UNAVAILABLE);
    }

    @Test
    void changedDatabaseLocationOrVersionCannotBeOverwritten() {
        Fixture moved = new Fixture();
        moved.database = new DatabaseVm(VM, TARGET, BASELINE, VmStatus.STOPPED, IDENTITY);
        rejected(moved, Rejection.LOCATION_CHANGED);
        Fixture edited = new Fixture();
        edited.database = new DatabaseVm(VM, SOURCE, BASELINE.plusSeconds(1), VmStatus.STOPPED, IDENTITY);
        rejected(edited, Rejection.LOCATION_CHANGED);
    }

    @Test
    void anotherLogicalVmOrChangedAccessIdentityCannotBeAdopted() {
        Fixture otherVm = new Fixture();
        otherVm.database = new DatabaseVm(UUID.randomUUID(), SOURCE, BASELINE, VmStatus.STOPPED, IDENTITY);
        rejected(otherVm, Rejection.IDENTITY_CHANGED);
        Fixture changedHostKeys = new Fixture();
        var changed = new GuestIdentity(IDENTITY.hostname(), IDENTITY.address(), IDENTITY.macAddress(), "4".repeat(64));
        changedHostKeys.database = new DatabaseVm(VM, SOURCE, BASELINE, VmStatus.STOPPED, changed);
        rejected(changedHostKeys, Rejection.IDENTITY_CHANGED);
    }

    @ParameterizedTest
    @MethodSource("unparkedStates")
    void runningCreatingOrDeletingDatabaseRowsAreNotRecoveryTargets(VmStatus state) {
        Fixture fixture = new Fixture();
        fixture.database = new DatabaseVm(VM, SOURCE, BASELINE, state, IDENTITY);
        rejected(fixture, Rejection.VM_NOT_PARKED);
    }

    static Stream<VmStatus> unparkedStates() {
        return Stream.of(VmStatus.RUNNING, VmStatus.CREATING, VmStatus.REBOOTING,
                VmStatus.DELETING, VmStatus.DELETED);
    }

    @Test
    void aVmidClaimedEvenByHistoricalDatabaseStateCannotBeReused() {
        Fixture fixture = new Fixture();
        fixture.claimed = Set.of(SOURCE.vmid(), TARGET.vmid());
        rejected(fixture, Rejection.TARGET_ALREADY_CLAIMED);
    }

    @Test
    void anExistingTargetIsNeverOverwrittenDuringRestorePreparation() {
        Fixture fixture = restored();
        rejected(fixture, Rejection.TARGET_EXISTS);
    }

    @ParameterizedTest
    @MethodSource("invalidReceipts")
    void taskSuccessAloneDoesNotProveWhichBackupWasRestored(RestoreReceipt receipt) {
        Fixture fixture = restored();
        rejectedAdoption(fixture, receipt, Rejection.RESTORE_NOT_CONFIRMED);
    }

    static Stream<RestoreReceipt> invalidReceipts() {
        return Stream.of(null,
                receipt(false, OP, BACKUP, TARGET, NOW.minusSeconds(2)),
                receipt(true, UUID.randomUUID(), BACKUP, TARGET, NOW.minusSeconds(2)),
                receipt(true, OP, "another-backup", TARGET, NOW.minusSeconds(2)),
                receipt(true, OP, BACKUP, SOURCE, NOW.minusSeconds(2)),
                receipt(true, OP, BACKUP, TARGET, NOW.plusSeconds(1)),
                receipt(true, OP, BACKUP, TARGET, NOW.minusSeconds(1000)),
                new RestoreReceipt(OP, TARGET, BACKUP, "9".repeat(64), restoreUpid(TARGET), true, NOW.minusSeconds(2)));
    }

    @ParameterizedTest
    @MethodSource("unrelatedTasks")
    void aSuccessfulUnrelatedTaskIsNotACompletedRestore(String upid) {
        rejectedAdoption(restored(), new RestoreReceipt(OP, TARGET, BACKUP, BACKUP_HASH,
                upid, true, NOW.minusSeconds(2)), Rejection.RESTORE_NOT_CONFIRMED);
    }

    static Stream<String> unrelatedTasks() {
        return Stream.of("", "OK", restoreUpid(SOURCE),
                restoreUpid(TARGET).replace("qmrestore", "qmstart"),
                restoreUpid(TARGET).replace("900001", "900002"));
    }

    @Test
    void aMissingOrDuplicatedRestoreTargetIsNotAdopted() {
        Fixture missing = new Fixture();
        rejectedAdoption(missing, goodReceipt(), Rejection.TARGET_MISSING_OR_DUPLICATED);
        Fixture duplicate = restored();
        duplicate.guests = List.of(isolated(SOURCE, null), isolated(TARGET, OP),
                isolated(new Location(3, "other-node", TARGET.vmid()), OP));
        rejectedAdoption(duplicate, goodReceipt(), Rejection.TARGET_MISSING_OR_DUPLICATED);
    }

    @Test
    void aGenericManagedTagOrMatchingNameIsNotAnOperationOwnershipProof() {
        Fixture fixture = new Fixture();
        fixture.guests = List.of(isolated(SOURCE, null), isolated(TARGET, null));
        rejectedAdoption(fixture, goodReceipt(), Rejection.TARGET_IDENTITY_MISMATCH);
    }

    @Test
    void restoredHostKeyIdentityCannotBeSilentlyReplaced() {
        Fixture fixture = new Fixture();
        var changed = new GuestIdentity(IDENTITY.hostname(), IDENTITY.address(), IDENTITY.macAddress(), "9".repeat(64));
        var target = new ObservedGuest(TARGET, changed, GuestKind.QEMU, RuntimeState.STOPPED,
                false, false, Set.of(), false, false, OP);
        fixture.guests = List.of(isolated(SOURCE, null), target);
        rejectedAdoption(fixture, goodReceipt(), Rejection.TARGET_IDENTITY_MISMATCH);
    }

    @Test
    void aRestoredVmCannotBeExposedBeforeTheGuardedLocationSwitch() {
        Fixture fixture = new Fixture();
        fixture.guests = List.of(isolated(SOURCE, null),
                guest(TARGET, RuntimeState.RUNNING, false, false, Set.of("net0"), false, false, OP));
        rejectedAdoption(fixture, goodReceipt(), Rejection.TARGET_NOT_ISOLATED);
    }

    @Test
    void anotherGuestWithTheSameAddressOrMacBlocksTheSwitchEvenWhenStopped() {
        Fixture fixture = new Fixture();
        var other = new Location(2, "target-node", 800001);
        fixture.guests = List.of(isolated(SOURCE, null), isolated(other, null));
        rejected(fixture, Rejection.DUPLICATE_NETWORK_IDENTITY);
    }

    @Test
    void manifestsCannotRestoreOverTheSourceVmid() {
        assertThatThrownBy(() -> new Manifest(OP, VM, 7, CA, SOURCE, BASELINE, SOURCE,
                BACKUP, BACKUP_HASH, IDENTITY)).isInstanceOf(IllegalArgumentException.class);
    }

    private static RestoreReceipt goodReceipt() {
        return receipt(true, OP, BACKUP, TARGET, NOW.minusSeconds(2));
    }

    private static RestoreReceipt receipt(boolean success, UUID operation, String backup, Location target, Instant completed) {
        return new RestoreReceipt(operation, target, backup, BACKUP_HASH, restoreUpid(target), success, completed);
    }

    private static String restoreUpid(Location target) {
        return "UPID:" + target.nodeName() + ":00000001:00000002:6A4E2CB0:qmrestore:"
                + target.vmid() + ":operator@pve:";
    }

    private static ExclusiveOwner owner(UUID operation, UUID vm, long token, Instant expires, int count) {
        return new ExclusiveOwner(operation, vm, token, NOW.minusSeconds(300), expires, count);
    }

    private static ObservedGuest isolated(Location location, UUID operation) {
        return guest(location, RuntimeState.STOPPED, false, false, Set.of(), false, false, operation);
    }

    private static ObservedGuest guest(Location location, RuntimeState state, boolean onboot, boolean ha,
            Set<String> interfaces, boolean pci, boolean task, UUID operation) {
        return new ObservedGuest(location, IDENTITY, GuestKind.QEMU, state, onboot, ha, interfaces, pci, task, operation);
    }

    private static Fixture restored() {
        Fixture fixture = new Fixture();
        fixture.guests = List.of(isolated(SOURCE, null), isolated(TARGET, OP));
        return fixture;
    }

    private static void rejected(Fixture fixture, Rejection reason) {
        assertThatThrownBy(() -> requireRestoreReady(fixture.manifest, fixture.observation(), NOW, MAX_AGE))
                .isInstanceOfSatisfying(RecoveryRejected.class, error -> assertThat(error.reason()).isEqualTo(reason));
    }

    private static void rejectedAdoption(Fixture fixture, RestoreReceipt receipt, Rejection reason) {
        assertThatThrownBy(() -> requireAdoptionReady(fixture.manifest, fixture.observation(), receipt, NOW, MAX_AGE))
                .isInstanceOfSatisfying(RecoveryRejected.class, error -> assertThat(error.reason()).isEqualTo(reason));
    }

    private static final class Fixture {
        private Manifest manifest = new Manifest(OP, VM, 7, CA, SOURCE, BASELINE, TARGET,
                BACKUP, BACKUP_HASH, IDENTITY);
        private Instant observedAt = NOW.minusSeconds(1);
        private boolean complete = true;
        private String ca = CA;
        private Set<Long> nodes = Set.of(SOURCE.nodeId(), TARGET.nodeId());
        private DatabaseVm database = new DatabaseVm(VM, SOURCE, BASELINE, VmStatus.STOPPED, IDENTITY);
        private ExclusiveOwner owner = owner(OP, VM, 7, NOW.plusSeconds(60), 1);
        private Activity activity = new Activity(true, 0, 0, 0, false, false);
        private List<ObservedGuest> guests = List.of(isolated(SOURCE, null));
        private Set<Integer> claimed = Set.of(SOURCE.vmid());

        private RecoveryObservation observation() {
            return new RecoveryObservation(observedAt, complete, ca, nodes, database, owner, activity, guests, claimed);
        }
    }
}
