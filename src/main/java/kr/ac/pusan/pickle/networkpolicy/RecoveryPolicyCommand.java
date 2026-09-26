package kr.ac.pusan.pickle.networkpolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import javax.sql.DataSource;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPolicyAdvisoryLock.OutcomeUnknownException;

/** One pinned recovery operation on one locked DB session, with no queue dispatch. */
public class RecoveryPolicyCommand {

    public record Pin(Path manifest, String manifestSha256, Path proof, String proofSha256,
            Path databaseIdentity, UUID attemptId) {
        public Pin {
            if (manifest == null || proof == null || databaseIdentity == null || attemptId == null) {
                throw new IllegalArgumentException("Recovery paths and attempt ID are required.");
            }
            RecoveryPolicyEvidence.sha(manifestSha256);
            RecoveryPolicyEvidence.sha(proofSha256);
        }
    }

    public enum Result { APPLIED, ALREADY_APPLIED }

    private final DataSource dataSource;
    private final VmNetworkPolicyAdvisoryLock lock;
    private final RecoveryPolicyDb database;
    private final RecoveryProviderVerifier provider;
    private final VmFirewallBarrierReconciler reconciler;
    private final VmFirewallPolicyProperties properties;
    private final String evidenceOwner;

    public RecoveryPolicyCommand(DataSource dataSource, VmNetworkPolicyAdvisoryLock lock,
            RecoveryPolicyDb database, RecoveryProviderVerifier provider,
            VmFirewallBarrierReconciler reconciler, VmFirewallPolicyProperties properties) {
        this.dataSource = dataSource;
        this.lock = lock;
        this.database = database;
        this.provider = provider;
        this.reconciler = reconciler;
        this.properties = properties;
        this.evidenceOwner = "root";
    }

    RecoveryPolicyCommand(DataSource dataSource, VmNetworkPolicyAdvisoryLock lock,
            RecoveryPolicyDb database, RecoveryProviderVerifier provider,
            VmFirewallBarrierReconciler reconciler, VmFirewallPolicyProperties properties,
            String evidenceOwner) {
        this.dataSource = dataSource;
        this.lock = lock;
        this.database = database;
        this.provider = provider;
        this.reconciler = reconciler;
        this.properties = properties;
        this.evidenceOwner = evidenceOwner;
    }

    public Result execute(Pin pin) {
        RecoveryPolicyEvidence proof = RecoveryPolicyEvidence.load(pin.manifest(),
                pin.manifestSha256(), pin.proof(), pin.proofSha256(), evidenceOwner);
        String identity = readIdentity(pin.databaseIdentity());
        long vmId;
        try (Connection initial = dataSource.getConnection()) {
            if (!initial.getAutoCommit()) {
                throw new IllegalStateException("Initial DB connection is not in autocommit mode.");
            }
            vmId = database.resolveVmId(initial, proof.vmPublicId());
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot resolve the pinned VM.", failure);
        }
        try {
            return lock.runOnConnection(vmId,
                    connection -> runLocked(connection, vmId, proof, pin, identity));
        } catch (OutcomeUnknownException uncertain) {
            throw new OutcomeUnknownException("Keep both guests isolated. Read-only result: "
                    + readOnlyOutcome(proof, pin, identity), uncertain);
        }
    }

    Result runLocked(Connection connection, long vmId, RecoveryPolicyEvidence proof,
            Pin pin, String identity) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new OutcomeUnknownException("Recovery lock session entered a transaction.", null);
        }
        properties.requireConfigured();
        database.requireIdentity(connection, identity, proof);
        VmNetworkPolicyApplyState prior = priorState(connection, proof, pin);
        RecoveryPolicyDb.Snapshot initial = database.snapshot(connection, vmId, proof, false, prior);
        verifyProvider(proof, initial);
        if (prior == VmNetworkPolicyApplyState.APPLIED) {
            reconciler.verifyApplied(initial.target().providerTarget(), initial.plan(properties),
                    "stopped");
            verifyProvider(proof, initial);
            return Result.ALREADY_APPLIED;
        }
        database.insertAudit(connection, proof, pin.proofSha256(), pin.attemptId(),
                "vm.recovery_policy_apply_start");
        try {
            proof.requireWriteWindow(Instant.now());
            reconciler.reconcile(initial.target().providerTarget(), initial.plan(properties));
            reconciler.verifyApplied(initial.target().providerTarget(), initial.plan(properties),
                    "stopped");
            verifyProvider(proof, initial);
            database.requireIdentity(connection, identity, proof);
            finish(connection, vmId, proof, pin, initial, prior, identity);
            return Result.APPLIED;
        } catch (OutcomeUnknownException uncertain) {
            throw uncertain;
        } catch (RuntimeException | SQLException failure) {
            // Failure means enforcement was not proven. It does not assert that
            // the provider made no changes; both guests remain isolated.
            try {
                failConfirmed(connection, vmId, proof, pin, initial,
                        failure instanceof VmFirewallBarrierReconciler.FailedClosedException);
            } catch (OutcomeUnknownException uncertain) {
                uncertain.addSuppressed(failure);
                throw uncertain;
            } catch (RuntimeException | SQLException recordFailure) {
                failure.addSuppressed(recordFailure);
            }
            throw failure;
        }
    }

    private void verifyProvider(RecoveryPolicyEvidence proof, RecoveryPolicyDb.Snapshot snapshot) {
        provider.verify(proof, snapshot.sourceHost(), snapshot.target().apiHost(),
                snapshot.sourceBridge(), snapshot.target().bridge(), snapshot.sourceMtu(),
                snapshot.target().mtu());
    }

    private void finish(Connection connection, long vmId, RecoveryPolicyEvidence proof, Pin pin,
            RecoveryPolicyDb.Snapshot initial, VmNetworkPolicyApplyState state, String identity)
            throws SQLException {
        connection.setAutoCommit(false);
        boolean commitStarted = false;
        boolean safeToReset = false;
        try {
            database.requireIdentity(connection, identity, proof);
            RecoveryPolicyDb.Snapshot current = database.snapshot(connection, vmId, proof,
                    true, state);
            if (!current.equals(initial)) {
                throw RecoveryPolicyDb.changed();
            }
            database.markApplied(connection, proof, current, state);
            database.insertAudit(connection, proof, pin.proofSha256(), pin.attemptId(),
                    "vm.recovery_policy_applied");
            proof.requireCommitWindow(Instant.now());
            commitStarted = true;
            connection.commit();
            safeToReset = true;
        } catch (SQLException failure) {
            if (commitStarted) {
                throw new OutcomeUnknownException("Recovery success commit is uncertain.", failure);
            }
            rollback(connection, failure);
            safeToReset = true;
            throw failure;
        } catch (RuntimeException failure) {
            rollback(connection, failure);
            safeToReset = true;
            throw failure;
        } finally {
            if (safeToReset) {
                resetTransaction(connection);
            }
        }
    }

    private void failConfirmed(Connection connection, long vmId, RecoveryPolicyEvidence proof,
            Pin pin, RecoveryPolicyDb.Snapshot initial, boolean closed) throws SQLException {
        if (connection.isClosed() || !connection.getAutoCommit()) {
            return;
        }
        connection.setAutoCommit(false);
        boolean commitStarted = false;
        boolean safeToReset = false;
        try {
            RecoveryPolicyDb.Snapshot current = database.snapshot(connection, vmId, proof,
                    true, initial.state());
            if (!current.equals(initial)) {
                throw RecoveryPolicyDb.changed();
            }
            database.markFailed(connection, proof, current, closed);
            database.insertAudit(connection, proof, pin.proofSha256(), pin.attemptId(),
                    closed ? "vm.recovery_policy_failed_closed" : "vm.recovery_policy_failed");
            commitStarted = true;
            connection.commit();
            safeToReset = true;
        } catch (SQLException failure) {
            if (commitStarted) {
                throw new OutcomeUnknownException("Recovery failure commit is uncertain.", failure);
            }
            rollback(connection, failure);
            safeToReset = true;
            throw failure;
        } catch (RuntimeException failure) {
            rollback(connection, failure);
            safeToReset = true;
            throw failure;
        } finally {
            if (safeToReset) {
                resetTransaction(connection);
            }
        }
    }

    private VmNetworkPolicyApplyState priorState(Connection connection,
            RecoveryPolicyEvidence proof, Pin pin) throws SQLException {
        List<String[]> events = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select action, detail->>'attemptId', detail->>'policyProofSha256',
                       detail->>'manifestSha256' from audit_logs
                 where target_type = 'vm' and target_id = ?
                   and detail->>'operationId' = ?
                   and action in ('vm.recovery_policy_apply_start',
                                  'vm.recovery_policy_applied',
                                  'vm.recovery_policy_failed',
                                  'vm.recovery_policy_failed_closed')
                 order by id
                """)) {
            statement.setString(1, proof.vmPublicId().toString());
            statement.setString(2, proof.operationId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    events.add(new String[] {rows.getString(1), rows.getString(2),
                            rows.getString(3), rows.getString(4)});
                }
            }
        }
        List<String[]> completed = events.stream()
                .filter(event -> "vm.recovery_policy_applied".equals(event[0])).toList();
        if (!completed.isEmpty()) {
            if (completed.size() != 1
                    || !pin.attemptId().toString().equals(completed.getFirst()[1])
                    || !pin.proofSha256().equals(completed.getFirst()[2])
                    || !proof.manifestSha256().equals(completed.getFirst()[3])) {
                throw new IllegalStateException("Completed operation belongs to a different proof or attempt.");
            }
            return VmNetworkPolicyApplyState.APPLIED;
        }
        if (events.stream().anyMatch(event -> pin.attemptId().toString().equals(event[1]))) {
            throw new IllegalStateException("Recovery attempt ID was already used.");
        }
        if (events.isEmpty()) {
            return VmNetworkPolicyApplyState.PENDING;
        }
        String last = events.getLast()[0];
        if ("vm.recovery_policy_failed".equals(last)) {
            return VmNetworkPolicyApplyState.FAILED;
        }
        if ("vm.recovery_policy_failed_closed".equals(last)) {
            return VmNetworkPolicyApplyState.FAILED_CLOSED;
        }
        throw new IllegalStateException("Recovery attempt outcome needs review before retry.");
    }

    String readOnlyOutcome(RecoveryPolicyEvidence proof, Pin pin, String identity) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setReadOnly(true);
            database.requireIdentity(connection, identity, proof);
            try (PreparedStatement statement = connection.prepareStatement("""
                    select exists(
                        select 1 from vms v
                        join nodes n on n.id = v.node_id
                        join vm_network_policies p on p.vm_id = v.id
                        where v.public_id = ? and v.status = 'STOPPED'
                          and v.deleted_at is null and v.delete_kind is null
                          and v.updated_at = ? and n.public_id = ?
                          and v.proxmox_vmid = ?
                          and p.revision = ? and p.desired_generation = ?
                          and p.desired_hash = ? and p.apply_state = 'APPLIED'
                          and p.applied_generation = p.desired_generation
                          and p.applied_hash = p.desired_hash
                          and (select count(*) from audit_logs a
                                where a.action = 'vm.recovery_policy_applied'
                                  and a.actor_role = 'ROOT_OPERATOR'
                                  and a.target_type = 'vm'
                                  and a.target_id = v.public_id::text
                                  and a.detail->>'operationId' = ?
                                  and a.detail->>'attemptId' = ?
                                  and a.detail->>'manifestSha256' = ?
                                  and a.detail->>'policyProofSha256' = ?
                                  and a.detail->>'sourceNodeId' = ?
                                  and (a.detail->>'sourceVmid')::integer = ?
                                  and a.detail->>'targetNodeId' = ?
                                  and (a.detail->>'targetVmid')::integer = ?
                                  and (a.detail->>'fencingToken')::bigint = ?
                                  and (a.detail->>'generation')::bigint = ?
                                  and a.detail->>'desiredHash' = ?) = 1)
                    """)) {
                statement.setObject(1, proof.vmPublicId());
                statement.setObject(2, java.sql.Timestamp.from(proof.vmUpdatedAt()));
                statement.setObject(3, proof.target().nodePublicId());
                statement.setInt(4, proof.target().vmid());
                statement.setLong(5, proof.policy().revision());
                statement.setLong(6, proof.policy().generation());
                statement.setString(7, proof.policy().desiredHash());
                statement.setString(8, proof.operationId().toString());
                statement.setString(9, pin.attemptId().toString());
                statement.setString(10, proof.manifestSha256());
                statement.setString(11, pin.proofSha256());
                statement.setString(12, proof.source().nodePublicId().toString());
                statement.setInt(13, proof.source().vmid());
                statement.setString(14, proof.target().nodePublicId().toString());
                statement.setInt(15, proof.target().vmid());
                statement.setLong(16, proof.fencingToken());
                statement.setLong(17, proof.policy().generation());
                statement.setString(18, proof.policy().desiredHash());
                try (ResultSet rows = statement.executeQuery()) {
                    if (rows.next() && rows.getBoolean(1)) {
                        return "APPLIED_WITH_AUDIT";
                    }
                }
            }
            return "NOT_CONFIRMED";
        } catch (RuntimeException | SQLException failure) {
            return "UNAVAILABLE";
        }
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
            throw new OutcomeUnknownException("Recovery rollback is uncertain.", failure);
        }
    }

    private static void resetTransaction(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException failure) {
            throw new OutcomeUnknownException("Recovery transaction reset is uncertain.", failure);
        }
    }

    private static String readIdentity(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)
                    || Files.size(path) == 0 || Files.size(path) > 8192) {
                throw new IllegalArgumentException("DB connection identity file is invalid.");
            }
            return Files.readString(path);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read DB connection identity file.", failure);
        }
    }
}
