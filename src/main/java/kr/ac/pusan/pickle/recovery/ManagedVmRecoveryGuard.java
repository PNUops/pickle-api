package kr.ac.pusan.pickle.recovery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.networkpolicy.CidrBlock;
import kr.ac.pusan.pickle.vm.VmStatus;

/**
 * Pure checks for a manually restored VM retaining its logical identity.
 * Observations must be collected by trusted operational code, never accepted as
 * caller-provided assertions. Passing these checks neither restores a guest nor
 * acquires a lock, fences a source, proves a backup, or updates the database.
 */
public final class ManagedVmRecoveryGuard {

    private ManagedVmRecoveryGuard() {
    }

    public enum RuntimeState { STOPPED, RUNNING, UNKNOWN }
    public enum GuestKind { QEMU, LXC, TEMPLATE, UNKNOWN }

    public enum Rejection {
        STALE_OBSERVATION, INCOMPLETE_INVENTORY, WRONG_CLUSTER, TARGET_NODE_UNAVAILABLE,
        IDENTITY_CHANGED, LOCATION_CHANGED, VM_NOT_PARKED, OWNER_CONFLICT, ACTIVE_WORK,
        SOURCE_MISSING_OR_DUPLICATED, SOURCE_NOT_FENCED, TARGET_ALREADY_CLAIMED,
        TARGET_EXISTS, RESTORE_NOT_CONFIRMED, TARGET_MISSING_OR_DUPLICATED,
        TARGET_NOT_ISOLATED, TARGET_IDENTITY_MISMATCH, DUPLICATE_NETWORK_IDENTITY
    }

    public static void requireRestoreReady(Manifest manifest, RecoveryObservation observation,
            Instant now, Duration maximumObservationAge) {
        requireCommon(manifest, observation, now, maximumObservationAge);
        require(targets(manifest, observation).isEmpty(), Rejection.TARGET_EXISTS);
    }

    /** Returns only the expected-old compare-and-set inputs for a later guarded transaction. */
    public static LocationChange requireAdoptionReady(Manifest manifest, RecoveryObservation observation,
            RestoreReceipt receipt, Instant now, Duration maximumObservationAge) {
        requireCommon(manifest, observation, now, maximumObservationAge);
        require(receipt != null && receipt.succeeded()
                && manifest.operationId().equals(receipt.operationId())
                && manifest.target().equals(receipt.target())
                && manifest.backupReference().equals(receipt.backupReference())
                && manifest.backupManifestSha256().equals(receipt.backupManifestSha256())
                && isRestoreTaskFor(receipt.upid(), manifest.target())
                && !receipt.completedAt().isBefore(observation.owner().acquiredAt())
                && !receipt.completedAt().isAfter(observation.observedAt()), Rejection.RESTORE_NOT_CONFIRMED);

        List<ObservedGuest> candidates = targets(manifest, observation);
        require(candidates.size() == 1, Rejection.TARGET_MISSING_OR_DUPLICATED);
        ObservedGuest target = candidates.getFirst();
        require(manifest.target().equals(target.location())
                && manifest.operationId().equals(target.recoveryOperationId())
                && manifest.identity().equals(target.identity()), Rejection.TARGET_IDENTITY_MISMATCH);
        require(isolated(target), Rejection.TARGET_NOT_ISOLATED);

        return new LocationChange(manifest.operationId(), manifest.logicalVmId(),
                manifest.expectedSource(), manifest.expectedVmUpdatedAt(), manifest.target(),
                observation.owner().fencingToken(), observation.owner().expiresAt());
    }

    private static void requireCommon(Manifest manifest, RecoveryObservation observation,
            Instant now, Duration maximumAge) {
        Objects.requireNonNull(manifest);
        Objects.requireNonNull(observation);
        Objects.requireNonNull(now);
        if (maximumAge == null || maximumAge.isNegative() || maximumAge.isZero()) {
            throw new IllegalArgumentException("관측 유효 시간을 지정해 주세요.");
        }
        require(!observation.observedAt().isAfter(now)
                && !observation.observedAt().isBefore(now.minus(maximumAge)), Rejection.STALE_OBSERVATION);
        require(observation.inventoryComplete(), Rejection.INCOMPLETE_INVENTORY);
        require(manifest.clusterCaSha256().equals(observation.clusterCaSha256()), Rejection.WRONG_CLUSTER);
        require(observation.reachableNodeIds().contains(manifest.target().nodeId()), Rejection.TARGET_NODE_UNAVAILABLE);

        DatabaseVm vm = observation.vm();
        require(manifest.logicalVmId().equals(vm.logicalVmId())
                && manifest.identity().equals(vm.identity()), Rejection.IDENTITY_CHANGED);
        require(manifest.expectedSource().equals(vm.location())
                && manifest.expectedVmUpdatedAt().equals(vm.updatedAt()), Rejection.LOCATION_CHANGED);
        require(vm.status() == VmStatus.STOPPED || vm.status() == VmStatus.NEEDS_ADMIN
                || vm.status() == VmStatus.ERROR, Rejection.VM_NOT_PARKED);

        ExclusiveOwner owner = observation.owner();
        require(manifest.operationId().equals(owner.operationId())
                && manifest.logicalVmId().equals(owner.logicalVmId())
                && owner.activeOwnerCount() == 1 && owner.fencingToken() == manifest.ownerFencingToken()
                && !owner.acquiredAt().isAfter(observation.observedAt())
                && owner.expiresAt().isAfter(now), Rejection.OWNER_CONFLICT);
        Activity activity = observation.activity();
        require(activity.inventoryComplete() && activity.provisioningTasks() == 0
                && activity.powerDispatches() == 0 && activity.gpuOperations() == 0
                && !activity.pendingPowerAction() && !activity.pendingDeletion(), Rejection.ACTIVE_WORK);

        List<ObservedGuest> sources = observation.guests().stream()
                .filter(guest -> guest.location().vmid() == manifest.expectedSource().vmid()).toList();
        require(sources.size() == 1, Rejection.SOURCE_MISSING_OR_DUPLICATED);
        ObservedGuest source = sources.getFirst();
        require(manifest.expectedSource().equals(source.location())
                && manifest.identity().equals(source.identity()) && isolated(source), Rejection.SOURCE_NOT_FENCED);
        require(!observation.claimedDatabaseVmids().contains(manifest.target().vmid()), Rejection.TARGET_ALREADY_CLAIMED);

        // Unrelated stopped guests can still start later: sharing either identity is a conflict.
        boolean anotherOwner = observation.guests().stream()
                .filter(guest -> guest.location().vmid() != manifest.expectedSource().vmid()
                        && guest.location().vmid() != manifest.target().vmid())
                .anyMatch(guest -> guest.identity().address().equals(manifest.identity().address())
                        || guest.identity().macAddress().equals(manifest.identity().macAddress()));
        require(!anotherOwner, Rejection.DUPLICATE_NETWORK_IDENTITY);
    }

    private static List<ObservedGuest> targets(Manifest manifest, RecoveryObservation observation) {
        return observation.guests().stream()
                .filter(guest -> guest.location().vmid() == manifest.target().vmid()).toList();
    }

    private static boolean isolated(ObservedGuest guest) {
        return guest.kind() == GuestKind.QEMU && guest.runtimeState() == RuntimeState.STOPPED && !guest.onboot()
                && !guest.managedByHa() && guest.connectedInterfaces().isEmpty()
                && !guest.pciPassthrough() && !guest.pendingPveTask();
    }

    private static boolean isRestoreTaskFor(String upid, Location target) {
        if (upid == null || upid.length() > 1024) {
            return false;
        }
        String[] fields = upid.split(":", -1);
        return fields.length == 9 && fields[0].equals("UPID")
                && fields[1].equals(target.nodeName()) && fields[2].matches("[0-9a-fA-F]+")
                && fields[3].matches("[0-9a-fA-F]+") && fields[4].matches("[0-9a-fA-F]+")
                && fields[5].equals("qmrestore") && fields[6].equals(Integer.toString(target.vmid()))
                && !fields[7].isBlank() && fields[8].isEmpty();
    }

    private static void require(boolean condition, Rejection reason) {
        if (!condition) {
            throw new RecoveryRejected(reason);
        }
    }

    public record Location(long nodeId, String nodeName, int vmid) {
        public Location {
            if (nodeId <= 0 || nodeName == null || nodeName.isBlank() || vmid <= 0) {
                throw new IllegalArgumentException("VM 위치가 올바르지 않습니다.");
            }
        }
    }

    /** No private key material belongs in a recovery manifest. */
    public record GuestIdentity(String hostname, String address, String macAddress, String hostKeysSha256) {
        public GuestIdentity {
            if (hostname == null || hostname.isBlank() || macAddress == null
                    || !macAddress.matches("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}")) {
                throw new IllegalArgumentException("복구할 VM의 네트워크 신원이 올바르지 않습니다.");
            }
            address = CidrBlock.host(address).toString().split("/", 2)[0];
            macAddress = macAddress.toLowerCase(java.util.Locale.ROOT);
            hostKeysSha256 = requireSha256(hostKeysSha256);
        }
    }

    public record Manifest(UUID operationId, UUID logicalVmId, long ownerFencingToken, String clusterCaSha256,
            Location expectedSource, Instant expectedVmUpdatedAt, Location target,
            String backupReference, String backupManifestSha256, GuestIdentity identity) {
        public Manifest {
            Objects.requireNonNull(operationId);
            Objects.requireNonNull(logicalVmId);
            Objects.requireNonNull(expectedSource);
            Objects.requireNonNull(expectedVmUpdatedAt);
            Objects.requireNonNull(target);
            Objects.requireNonNull(identity);
            clusterCaSha256 = requireSha256(clusterCaSha256);
            backupManifestSha256 = requireSha256(backupManifestSha256);
            if (ownerFencingToken <= 0 || expectedSource.vmid() == target.vmid()
                    || backupReference == null || backupReference.isBlank()) {
                throw new IllegalArgumentException("원본과 다른 복구 VMID 및 확인된 백업을 지정해 주세요.");
            }
        }
    }

    public record DatabaseVm(UUID logicalVmId, Location location, Instant updatedAt,
            VmStatus status, GuestIdentity identity) {
        public DatabaseVm {
            Objects.requireNonNull(logicalVmId);
            Objects.requireNonNull(location);
            Objects.requireNonNull(updatedAt);
            Objects.requireNonNull(status);
            Objects.requireNonNull(identity);
        }
    }

    public record ExclusiveOwner(UUID operationId, UUID logicalVmId, long fencingToken,
            Instant acquiredAt, Instant expiresAt, int activeOwnerCount) {
        public ExclusiveOwner {
            Objects.requireNonNull(acquiredAt);
            Objects.requireNonNull(expiresAt);
        }
    }

    /** Counts include queued, retrying and parked operations, not only running workers. */
    public record Activity(boolean inventoryComplete, int provisioningTasks, int powerDispatches,
            int gpuOperations, boolean pendingPowerAction, boolean pendingDeletion) {
        public Activity {
            if (provisioningTasks < 0 || powerDispatches < 0 || gpuOperations < 0) {
                throw new IllegalArgumentException("진행 중인 작업 수가 올바르지 않습니다.");
            }
        }
    }

    public record ObservedGuest(Location location, GuestIdentity identity, GuestKind kind, RuntimeState runtimeState,
            boolean onboot, boolean managedByHa, Set<String> connectedInterfaces,
            boolean pciPassthrough, boolean pendingPveTask, UUID recoveryOperationId) {
        public ObservedGuest {
            Objects.requireNonNull(location);
            Objects.requireNonNull(identity);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(runtimeState);
            connectedInterfaces = Set.copyOf(connectedInterfaces);
        }
    }

    public record RecoveryObservation(Instant observedAt, boolean inventoryComplete, String clusterCaSha256,
            Set<Long> reachableNodeIds, DatabaseVm vm, ExclusiveOwner owner, Activity activity,
            List<ObservedGuest> guests, Set<Integer> claimedDatabaseVmids) {
        public RecoveryObservation {
            Objects.requireNonNull(observedAt);
            clusterCaSha256 = requireSha256(clusterCaSha256);
            reachableNodeIds = Set.copyOf(reachableNodeIds);
            Objects.requireNonNull(vm);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(activity);
            guests = List.copyOf(guests);
            claimedDatabaseVmids = Set.copyOf(claimedDatabaseVmids);
        }
    }

    /** The operation's successful restore receipt must be independently read back before adoption. */
    public record RestoreReceipt(UUID operationId, Location target, String backupReference,
            String backupManifestSha256, String upid, boolean succeeded, Instant completedAt) {
        public RestoreReceipt {
            Objects.requireNonNull(completedAt);
            backupManifestSha256 = requireSha256(backupManifestSha256);
        }
    }

    /** Every field is a required compare-and-set condition, including the unexpired owner token. */
    public record LocationChange(UUID operationId, UUID logicalVmId, Location expectedSource,
            Instant expectedVmUpdatedAt, Location target, long ownerFencingToken, Instant ownerExpiresAt) {
    }

    public static final class RecoveryRejected extends IllegalStateException {
        private final Rejection reason;

        private RecoveryRejected(Rejection reason) {
            super("VM 복구 조건이 충족되지 않았습니다. 상태를 다시 확인해 주세요. (" + reason + ")");
            this.reason = reason;
        }

        public Rejection reason() {
            return reason;
        }
    }

    private static String requireSha256(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("확인된 SHA-256 지문을 지정해 주세요.");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }
}
