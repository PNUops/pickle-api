package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.doNothing;
import static org.mockito.ArgumentMatchers.any;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.sql.Connection;
import java.sql.SQLException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Exercises the recovery CAS against the actual migrated PostgreSQL schema. */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class RecoveryPolicyDbIntegrationTest {

    private static final String HOST_KEY = "ssh-ed25519 recovery-fixture";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pickle.vm-firewall.enabled", () -> "true");
        registry.add("pickle.vm-firewall.barrier-group", () -> "pickle-guard");
        registry.add("pickle.vm-firewall.ssh-gateway-source-ips", () -> "198.51.100.10");
        registry.add("pickle.vm-firewall.terminal-source-ips", () -> "198.51.100.20");
        registry.add("pickle.vm-firewall.proxy-source-ips", () -> "198.51.100.30");
        registry.add("pickle.vm-firewall.relay-source-ips", () -> "198.51.100.40");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired VmFirewallPolicyProperties properties;

    private RecoveryPolicyEvidence proof;
    private long vmId;

    @BeforeEach
    void prepare() {
        long orgId = SeedFixtures.seedOrgId(jdbc);
        long actorId = SeedFixtures.sysadminId(jdbc);
        long workspaceId = jdbc.queryForObject("""
                insert into workspaces (kind, name) values ('PROJECT', ?) returning id
                """, Long.class, "recovery-" + UUID.randomUUID());
        long requestId = RequestFixtures.insertVmRequest(jdbc, workspaceId, orgId,
                actorId, "Recovery policy fixture", null, 1, 1024, 10);
        long targetId = jdbc.queryForObject("select min(id) from nodes", Long.class);
        jdbc.update("""
                update nodes set labels = coalesce(labels, '{}'::jsonb)
                    || '{"vm_firewall_policy":{"schema_version":1},
                         "vm_nic_requirements":{"schema_version":1,
                         "mtu":1370,"firewall":true}}'::jsonb
                 where id = ?
                """, targetId);
        long sourceId = jdbc.queryForObject("""
                insert into nodes (name, api_host, status, cpu_threads, memory_mb,
                                   vm_bridge, storage, ip_pool_id, labels)
                select ?, ?, status, cpu_threads, memory_mb, vm_bridge, storage,
                       ip_pool_id, labels from nodes where id = ? returning id
                """, Long.class, "source-" + UUID.randomUUID(),
                "https://source.example.test:8006", targetId);
        String hostname = "recovery-" + UUID.randomUUID().toString().substring(0, 8);
        vmId = jdbc.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid,
                                 status, ssh_host_key)
                values (?, ?, ?, ?, ?, ?, (select min(id) from os_images),
                        1, 1024, 10, ?, 'STOPPED', ?) returning id
                """, Long.class, targetId, workspaceId, orgId, requestId,
                hostname, hostname, 900_000 + vmIdSequence(), HOST_KEY);
        long poolId = jdbc.queryForObject("select ip_pool_id from nodes where id = ?",
                Long.class, targetId);
        String ip = "192.0.2." + (vmId % 200 + 20);
        long allocationId = jdbc.queryForObject("""
                insert into ip_allocations(pool_id, ip, vm_id, status, allocated_at)
                values (?, ?::inet, ?, 'ALLOCATED', now()) returning id
                """, Long.class, poolId, ip, vmId);
        jdbc.update("update vms set ip_allocation_id = ? where id = ?", allocationId, vmId);
        String desiredHash = VmNetworkPolicyHashes.desired(properties, List.of(), List.of());
        jdbc.update("""
                insert into vm_network_policies(vm_id, revision, desired_generation,
                    applied_generation, desired_hash, applied_hash, apply_state)
                values (?, 2, 4, 3, ?, ?, 'PENDING')
                """, vmId, desiredHash, desiredHash);
        UUID vmPublicId = SeedFixtures.publicId(jdbc, "vms", vmId);
        UUID sourcePublicId = SeedFixtures.publicId(jdbc, "nodes", sourceId);
        UUID targetPublicId = SeedFixtures.publicId(jdbc, "nodes", targetId);
        Instant updatedAt = jdbc.queryForObject("select updated_at from vms where id = ?",
                java.sql.Timestamp.class, vmId).toInstant();
        String dbName = jdbc.queryForObject("select current_database()", String.class);
        String systemId = jdbc.queryForObject(
                "select system_identifier::text from pg_control_system()", String.class);
        String bridge = jdbc.queryForObject("select vm_bridge from nodes where id = ?",
                String.class, targetId);
        proof = new RecoveryPolicyEvidence(UUID.randomUUID(), 7, "a".repeat(64),
                "b".repeat(64), dbName, systemId, vmPublicId, updatedAt, "STOPPED",
                new RecoveryPolicyEvidence.Place(sourcePublicId,
                        jdbc.queryForObject("select name from nodes where id = ?",
                                String.class, sourceId), 800_000, "c".repeat(64), "d".repeat(40)),
                new RecoveryPolicyEvidence.Place(targetPublicId,
                        jdbc.queryForObject("select name from nodes where id = ?",
                                String.class, targetId),
                        jdbc.queryForObject("select proxmox_vmid from vms where id = ?",
                                Integer.class, vmId), "c".repeat(64), "e".repeat(40)),
                new RecoveryPolicyEvidence.Identity(hostname, ip, "02:00:00:00:00:10", bridge,
                        "ip=" + ip + "/24,gw=192.0.2.1", hash(HOST_KEY)),
                new RecoveryPolicyEvidence.Policy(2, 4, desiredHash, 3L, desiredHash, "PENDING"),
                Instant.now(), Instant.now().plusSeconds(600));
        insertAdoptionAudit();
    }

    @Test
    void rejectsChangedVmStatusAndReadsThePinnedSnapshot() throws Exception {
        RecoveryPolicyDb database = new RecoveryPolicyDb(properties);
        try (var connection = dataSource.getConnection()) {
            var snapshot = database.snapshot(connection, vmId, proof, false,
                    VmNetworkPolicyApplyState.PENDING);
            assertThat(snapshot.target().vmid()).isEqualTo(proof.target().vmid());
            jdbc.update("update vms set status = 'RUNNING' where id = ?", vmId);
            assertThatThrownBy(() -> database.snapshot(connection, vmId, proof, false,
                    VmNetworkPolicyApplyState.PENDING)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void failedCompletionAuditRollsBackAppliedState() throws Exception {
        RecoveryPolicyDb database = new RecoveryPolicyDb(properties);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var snapshot = database.snapshot(connection, vmId, proof, true,
                    VmNetworkPolicyApplyState.PENDING);
            database.markApplied(connection, proof, snapshot, VmNetworkPolicyApplyState.PENDING);
            assertThatThrownBy(() -> database.insertAudit(connection, proof,
                    "f".repeat(64), UUID.randomUUID(), null))
                    .isInstanceOf(java.sql.SQLException.class);
            connection.rollback();
        }
        assertThat(jdbc.queryForObject("select apply_state::text from vm_network_policies"
                + " where vm_id = ?", String.class, vmId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs"
                + " where action = 'vm.recovery_policy_applied' and target_id = ?",
                Long.class, proof.vmPublicId().toString())).isZero();
    }

    @Test
    void duplicateSuccessUsesOnlyReadbackAndExactAttemptAudit() throws Exception {
        RecoveryPolicyDb database = new RecoveryPolicyDb(properties);
        UUID attempt = UUID.randomUUID();
        String proofSha = "f".repeat(64);
        RecoveryPolicyCommand.Pin pin = pin(attempt, proofSha);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var snapshot = database.snapshot(connection, vmId, proof, true,
                    VmNetworkPolicyApplyState.PENDING);
            database.markApplied(connection, proof, snapshot, VmNetworkPolicyApplyState.PENDING);
            database.insertAudit(connection, proof, proofSha, attempt,
                    "vm.recovery_policy_applied");
            connection.commit();
        }
        RecoveryProviderVerifier provider = mock(RecoveryProviderVerifier.class);
        VmFirewallBarrierReconciler reconciler = mock(VmFirewallBarrierReconciler.class);
        RecoveryPolicyCommand command = command(database, provider, reconciler);
        String identity = dbIdentity();
        assertThat(command.readOnlyOutcome(proof, pin, identity)).isEqualTo("APPLIED_WITH_AUDIT");
        assertThat(command.readOnlyOutcome(proof, pin(attempt, "0".repeat(64)), identity))
                .isEqualTo("NOT_CONFIRMED");
        try (var connection = dataSource.getConnection()) {
            assertThat(command.runLocked(connection, vmId, proof, pin, identity))
                    .isEqualTo(RecoveryPolicyCommand.Result.ALREADY_APPLIED);
            assertThatThrownBy(() -> command.runLocked(connection, vmId, proof,
                    pin(attempt, "0".repeat(64)), identity))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("different proof or attempt");
            assertThatThrownBy(() -> command.runLocked(connection, vmId, proof,
                    pin(UUID.randomUUID(), proofSha), identity))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("different proof or attempt");
        }
        verify(reconciler, never()).reconcile(any(), any());
        jdbc.update("update vms set status = 'RUNNING' where id = ?", vmId);
        assertThat(command.readOnlyOutcome(proof, pin, identity)).isEqualTo("NOT_CONFIRMED");
    }

    @Test
    void shortOwnerLeaseRejectsBeforeProviderWriteAndNeverPublishesApplied() throws Exception {
        RecoveryPolicyEvidence expiring = new RecoveryPolicyEvidence(proof.operationId(),
                proof.fencingToken(), proof.manifestSha256(), proof.restoreReceiptSha256(),
                proof.databaseName(), proof.systemIdentifier(), proof.vmPublicId(),
                proof.vmUpdatedAt(), proof.vmStatus(), proof.source(), proof.target(),
                proof.identity(), proof.policy(), proof.observedAt(), Instant.now().plusSeconds(30));
        RecoveryPolicyDb database = new RecoveryPolicyDb(properties);
        RecoveryProviderVerifier provider = mock(RecoveryProviderVerifier.class);
        VmFirewallBarrierReconciler reconciler = mock(VmFirewallBarrierReconciler.class);
        RecoveryPolicyCommand command = command(database, provider, reconciler);
        try (var connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> command.runLocked(connection, vmId, expiring,
                    pin(UUID.randomUUID(), "f".repeat(64)), dbIdentity()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("lease");
        }
        verify(reconciler, never()).reconcile(any(), any());
        assertThat(jdbc.queryForObject("select apply_state::text from vm_network_policies"
                + " where vm_id = ?", String.class, vmId)).isNotEqualTo("APPLIED");
    }

    @Test
    void lostCommitResponseKeepsAppliedAndNeverOverwritesItWithFailed() throws Exception {
        RecoveryPolicyDb database = new RecoveryPolicyDb(properties);
        RecoveryProviderVerifier provider = mock(RecoveryProviderVerifier.class);
        VmFirewallBarrierReconciler reconciler = mock(VmFirewallBarrierReconciler.class);
        RecoveryPolicyCommand command = command(database, provider, reconciler);
        UUID attempt = UUID.randomUUID();
        RecoveryPolicyCommand.Pin pin = pin(attempt, "f".repeat(64));
        try (Connection real = dataSource.getConnection()) {
            Connection lostReply = (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> {
                        try {
                            Object result = method.invoke(real, args);
                            if ("commit".equals(method.getName())) {
                                throw new SQLException("commit response lost after durable commit");
                            }
                            return result;
                        } catch (InvocationTargetException wrapper) {
                            throw wrapper.getCause();
                        }
                    });
            assertThatThrownBy(() -> command.runLocked(lostReply, vmId, proof, pin, dbIdentity()))
                    .isInstanceOf(VmNetworkPolicyAdvisoryLock.OutcomeUnknownException.class);
        }
        assertThat(jdbc.queryForObject("select apply_state::text from vm_network_policies"
                + " where vm_id = ?", String.class, vmId)).isEqualTo("APPLIED");
        assertThat(command.readOnlyOutcome(proof, pin, dbIdentity()))
                .isEqualTo("APPLIED_WITH_AUDIT");
    }

    @Test
    void providerPostcheckFailureCannotPublishApplied() throws Exception {
        RecoveryPolicyDb database = new RecoveryPolicyDb(properties);
        RecoveryProviderVerifier provider = mock(RecoveryProviderVerifier.class);
        VmFirewallBarrierReconciler reconciler = mock(VmFirewallBarrierReconciler.class);
        doNothing().doThrow(new IllegalStateException("Provider target changed after apply."))
                .when(provider).verify(any(), any(), any(), any(), any(),
                        org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.anyInt());
        RecoveryPolicyCommand command = command(database, provider, reconciler);
        try (var connection = dataSource.getConnection()) {
            assertThatThrownBy(() -> command.runLocked(connection, vmId, proof,
                    pin(UUID.randomUUID(), "f".repeat(64)), dbIdentity()))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(reconciler).reconcile(any(), any());
        assertThat(jdbc.queryForObject("select apply_state::text from vm_network_policies"
                + " where vm_id = ?", String.class, vmId)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select count(*) from audit_logs"
                + " where action = 'vm.recovery_policy_applied' and target_id = ?",
                Long.class, proof.vmPublicId().toString())).isZero();
    }

    private RecoveryPolicyCommand command(RecoveryPolicyDb database,
            RecoveryProviderVerifier provider, VmFirewallBarrierReconciler reconciler) {
        return new RecoveryPolicyCommand(dataSource,
                new VmNetworkPolicyAdvisoryLock(dataSource), database, provider,
                reconciler, properties);
    }

    private static RecoveryPolicyCommand.Pin pin(UUID attempt, String proofSha) {
        return new RecoveryPolicyCommand.Pin(java.nio.file.Path.of("manifest"), "a".repeat(64),
                java.nio.file.Path.of("proof"), proofSha,
                java.nio.file.Path.of("identity"), attempt);
    }

    private String dbIdentity() {
        return jdbc.queryForObject("""
                select jsonb_build_object(
                    'database', current_database(), 'user', current_user,
                    'local_socket', inet_server_addr() is null,
                    'primary', not pg_is_in_recovery(),
                    'system_identifier', (select system_identifier::text from pg_control_system()))::text
                """, String.class);
    }

    private void insertAdoptionAudit() {
        jdbc.update("""
                insert into audit_logs(actor_id, actor_role, action, target_type, target_id,
                                       detail, ip)
                values (null, 'ROOT_OPERATOR', 'vm.recovery_adopt', 'vm', ?,
                        jsonb_build_object(
                          'operationId', ?::text, 'manifestSha256', ?::text,
                          'restoreReceiptSha256', ?::text,
                          'sourceNodeId', ?::text, 'targetNodeId', ?::text,
                          'sourceVmid', ?::integer, 'targetVmid', ?::integer,
                          'fencingToken', ?::bigint, 'adoptedVmUpdatedAt', ?::text,
                          'policyRevision', ?::bigint, 'policyDesiredGeneration', ?::bigint,
                          'policyDesiredHash', ?::text,
                          'policyAppliedGeneration', ?::bigint,
                          'policyAppliedHash', ?::text,
                          'sourceConfigDigest', ?::text, 'targetConfigDigest', ?::text,
                          'policyGenerationBefore', 3, 'policyGenerationPending', 4), null)
                """, proof.vmPublicId().toString(), proof.operationId().toString(),
                proof.manifestSha256(), proof.restoreReceiptSha256(),
                proof.source().nodePublicId().toString(), proof.target().nodePublicId().toString(),
                proof.source().vmid(), proof.target().vmid(), proof.fencingToken(),
                proof.vmUpdatedAt().toString(), proof.policy().revision(),
                proof.policy().generation(), proof.policy().desiredHash(),
                proof.policy().appliedGeneration(), proof.policy().appliedHash(),
                proof.source().providerDigest(), proof.target().providerDigest());
    }

    private static int vmIdSequence() {
        return java.util.concurrent.ThreadLocalRandom.current().nextInt(10000);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
