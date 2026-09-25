package kr.ac.pusan.pickle.networkpolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** All protected recovery SQL runs on the caller's locked JDBC connection. */
public class RecoveryPolicyDb {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Snapshot(VmNetworkPolicyStore.Target target, Instant vmUpdatedAt,
            String hostname, String sshHostKeySha256, UUID sourceNodePublicId,
            String sourceHost, String sourceName, String sourceBridge, int sourceMtu,
            long revision, long generation, String desiredHash, Long appliedGeneration,
            String appliedHash, VmNetworkPolicyApplyState state, Instant policyUpdatedAt,
            List<VmNetworkRule> rules,
            List<VmNetworkPolicyCompiler.PublishedPath> paths) {
        public Snapshot {
            rules = List.copyOf(rules);
            paths = List.copyOf(paths);
        }

        public VmFirewallBarrierReconciler.Target sourceTarget(int vmid) {
            return new VmFirewallBarrierReconciler.Target(sourceHost, sourceName,
                    vmid, sourceBridge, sourceMtu);
        }

        public VmNetworkPolicyCompiler.Plan plan(VmFirewallPolicyProperties properties) {
            return VmNetworkPolicyCompiler.compile(target.ip(), properties, paths, rules);
        }
    }

    private final VmFirewallPolicyProperties properties;

    public RecoveryPolicyDb(VmFirewallPolicyProperties properties) {
        this.properties = properties;
    }

    /** The sole permitted pre-lock query, used only to choose the advisory key. */
    public long resolveVmId(Connection connection, UUID vmPublicId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from vms where public_id = ?")) {
            statement.setObject(1, vmPublicId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw changed();
                }
                long id = rows.getLong(1);
                if (rows.next()) {
                    throw changed();
                }
                return id;
            }
        }
    }

    public void requireIdentity(Connection connection, String expectedJson,
            RecoveryPolicyEvidence proof) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select jsonb_build_object(
                    'database', current_database(), 'user', current_user,
                    'local_socket', inet_server_addr() is null,
                    'primary', not pg_is_in_recovery(),
                    'system_identifier', (select system_identifier::text from pg_control_system()))
                    = ?::jsonb,
                    current_database(),
                    (select system_identifier::text from pg_control_system()),
                    not pg_is_in_recovery()
                """)) {
            statement.setString(1, expectedJson);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !rows.getBoolean(1)
                        || !proof.databaseName().equals(rows.getString(2))
                        || !proof.systemIdentifier().equals(rows.getString(3))
                        || !rows.getBoolean(4)) {
                    throw changed();
                }
            }
        }
    }

    public Snapshot snapshot(Connection connection, long vmId,
            RecoveryPolicyEvidence proof, boolean lockRows,
            VmNetworkPolicyApplyState expectedState) throws SQLException {
        String sql = """
                select v.id, v.public_id, v.updated_at as vm_updated_at,
                       v.status::text as vm_status, v.deleted_at, v.delete_kind::text,
                       v.hostname, v.ssh_host_key,
                       v.node_id, v.proxmox_vmid, v.ip_allocation_id,
                       v.pending_power_action, v.power_operation_id,
                       n.public_id as target_public_id, n.status::text as target_status,
                       n.api_host as target_host,
                       n.name as target_name, n.vm_bridge as target_bridge,
                       (n.labels #>> '{vm_nic_requirements,mtu}')::integer as target_mtu,
                       n.labels #>> '{vm_nic_requirements,firewall}' as target_firewall,
                       n.labels #>> '{vm_nic_requirements,schema_version}' as target_nic_schema,
                       n.labels #>> '{vm_firewall_policy,schema_version}' as target_policy_schema,
                       s.public_id as source_public_id, s.status::text as source_status,
                       s.api_host as source_host,
                       s.name as source_name, s.vm_bridge as source_bridge,
                       (s.labels #>> '{vm_nic_requirements,mtu}')::integer as source_mtu,
                       s.labels #>> '{vm_nic_requirements,firewall}' as source_firewall,
                       s.labels #>> '{vm_nic_requirements,schema_version}' as source_nic_schema,
                       a.id as allocation_id, a.vm_id as allocation_vm_id,
                       a.status::text as allocation_status, host(a.ip) as allocation_ip,
                       p.revision, p.desired_generation, p.desired_hash,
                       p.applied_generation, p.applied_hash, p.apply_state::text,
                       p.updated_at as policy_updated_at
                  from vms v
                  join nodes n on n.id = v.node_id
                  join nodes s on s.public_id = ?
                  join ip_allocations a on a.id = v.ip_allocation_id
                  join vm_network_policies p on p.vm_id = v.id
                 where v.id = ?
                """ + (lockRows ? " for update of v, n, s, a, p" : "");
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, proof.source().nodePublicId());
            statement.setLong(2, vmId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw changed();
                }
                requireBase(rows, proof, vmId);
                VmNetworkPolicyStore.Target target = new VmNetworkPolicyStore.Target(vmId,
                        rows.getLong("node_id"), rows.getString("target_host"),
                        rows.getString("target_name"), rows.getInt("proxmox_vmid"),
                        rows.getString("target_bridge"), rows.getInt("target_mtu"),
                        rows.getLong("allocation_id"), rows.getString("allocation_ip"),
                        rows.getString("vm_status"));
                Snapshot snapshot = new Snapshot(target,
                        rows.getTimestamp("vm_updated_at").toInstant(),
                        rows.getString("hostname"), hash(rows.getString("ssh_host_key")),
                        rows.getObject("source_public_id", UUID.class),
                        rows.getString("source_host"), rows.getString("source_name"),
                        rows.getString("source_bridge"), rows.getInt("source_mtu"),
                        rows.getLong("revision"), rows.getLong("desired_generation"),
                        rows.getString("desired_hash"),
                        rows.getObject("applied_generation", Long.class),
                        rows.getString("applied_hash"),
                        VmNetworkPolicyApplyState.valueOf(rows.getString("apply_state")),
                        rows.getTimestamp("policy_updated_at").toInstant(),
                        rules(connection, vmId), paths(connection, vmId));
                if (rows.next()) {
                    throw changed();
                }
                requirePolicy(snapshot, proof, expectedState);
                requireNoWork(connection, vmId);
                requireAdoptionAudit(connection, proof);
                return snapshot;
            }
        }
    }

    private void requireBase(ResultSet rows, RecoveryPolicyEvidence proof,
            long vmId) throws SQLException {
        if (!proof.vmPublicId().equals(rows.getObject("public_id", UUID.class))
                || !"STOPPED".equals(rows.getString("vm_status"))
                || rows.getObject("deleted_at") != null || rows.getObject("delete_kind") != null
                || rows.getObject("pending_power_action") != null
                || rows.getObject("power_operation_id") != null
                || !proof.vmUpdatedAt().equals(rows.getTimestamp("vm_updated_at").toInstant())
                || !proof.identity().hostname().equals(rows.getString("hostname"))
                || !proof.identity().sshHostKeySha256().equals(hash(rows.getString("ssh_host_key")))
                || !proof.target().nodePublicId().equals(rows.getObject("target_public_id", UUID.class))
                || !proof.source().nodePublicId().equals(rows.getObject("source_public_id", UUID.class))
                || !proof.target().name().equals(rows.getString("target_name"))
                || !proof.source().name().equals(rows.getString("source_name"))
                || !"ACTIVE".equals(rows.getString("target_status"))
                || !"ACTIVE".equals(rows.getString("source_status"))
                || rows.getInt("proxmox_vmid") != proof.target().vmid()
                || rows.getLong("allocation_vm_id") != vmId
                || !"ALLOCATED".equals(rows.getString("allocation_status"))
                || !proof.identity().ip().equals(rows.getString("allocation_ip"))
                || !proof.identity().bridge().equals(rows.getString("target_bridge"))
                || !proof.identity().bridge().equals(rows.getString("source_bridge"))
                || rows.getInt("target_mtu") != 1370 || rows.getInt("source_mtu") != 1370
                || !"true".equals(rows.getString("target_firewall"))
                || !"true".equals(rows.getString("source_firewall"))
                || !"1".equals(rows.getString("target_nic_schema"))
                || !"1".equals(rows.getString("source_nic_schema"))
                || !"1".equals(rows.getString("target_policy_schema"))) {
            throw changed();
        }
    }

    private void requirePolicy(Snapshot actual, RecoveryPolicyEvidence proof,
            VmNetworkPolicyApplyState expectedState) {
        RecoveryPolicyEvidence.Policy expected = proof.policy();
        if (actual.revision() != expected.revision()
                || actual.generation() != expected.generation()
                || !actual.desiredHash().equals(expected.desiredHash())
                || !actual.sshHostKeySha256().equals(proof.identity().sshHostKeySha256())
                || !actual.sourceNodePublicId().equals(proof.source().nodePublicId())
                || !actual.sourceName().equals(proof.source().name())
                || !actual.target().nodeName().equals(proof.target().name())
                || !actual.target().bridge().equals(proof.identity().bridge())
                || !VmNetworkPolicyHashes.desired(properties, actual.paths(), actual.rules())
                        .equals(actual.desiredHash())) {
            throw changed();
        }
        if (actual.state() != expectedState) {
            throw changed();
        }
        if (expectedState == VmNetworkPolicyApplyState.APPLIED) {
            if (!Long.valueOf(actual.generation()).equals(actual.appliedGeneration())
                    || !actual.desiredHash().equals(actual.appliedHash())) {
                throw changed();
            }
        } else if (!expected.appliedGeneration().equals(actual.appliedGeneration())
                || !expected.appliedHash().equals(actual.appliedHash())) {
            throw changed();
        }
    }

    private static List<VmNetworkRule> rules(Connection connection, long vmId) throws SQLException {
        List<VmNetworkRule> rules = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select position, direction, action, protocol, peer::text,
                       port_start, port_end
                  from vm_network_policy_rules where vm_id = ? order by position
                """)) {
            statement.setLong(1, vmId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (rows.getInt("position") != rules.size()) {
                        throw changed();
                    }
                    rules.add(new VmNetworkRule(
                            VmNetworkRule.Direction.valueOf(rows.getString("direction")),
                            VmNetworkRule.Action.valueOf(rows.getString("action")),
                            VmNetworkRule.Protocol.valueOf(rows.getString("protocol")),
                            CidrBlock.parse(rows.getString("peer")),
                            rows.getObject("port_start", Integer.class),
                            rows.getObject("port_end", Integer.class)));
                }
            }
        }
        return List.copyOf(rules);
    }

    private List<VmNetworkPolicyCompiler.PublishedPath> paths(Connection connection,
            long vmId) throws SQLException {
        LinkedHashSet<VmNetworkPolicyCompiler.PublishedPath> paths = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                select source_kind::text, protocol, target_port
                  from vm_network_derived_paths where vm_id = ?
                 order by source_kind, protocol, target_port, id
                """)) {
            statement.setLong(1, vmId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    List<String> sources = "PROXY".equals(rows.getString(1))
                            ? properties.proxySourceIps() : properties.relaySourceIps();
                    VmNetworkRule.Protocol protocol = VmNetworkRule.Protocol.valueOf(
                            rows.getString(2));
                    int port = rows.getInt(3);
                    sources.forEach(source -> paths.add(
                            new VmNetworkPolicyCompiler.PublishedPath(source, protocol, port)));
                }
            }
        }
        return List.copyOf(paths);
    }

    private static void requireNoWork(Connection connection, long vmId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select exists(select 1 from provisioning_tasks
                               where vm_id = ? and status not in ('DONE','FAILED'))
                    or exists(select 1 from vm_power_dispatches where vm_id = ? and terminal = false)
                    or exists(select 1 from vm_network_path_operations
                               where vm_id = ? and phase <> 'DONE')
                    or exists(select 1 from gpu_allocations
                               where vm_id = ? and status not in ('RELEASED','CANCELED'))
                    or exists(select 1 from gpu_operations g
                               join gpu_allocations a on a.id = g.allocation_id
                              where a.vm_id = ? and g.phase in ('PENDING','RUNNING'))
                """)) {
            for (int i = 1; i <= 5; i++) {
                statement.setLong(i, vmId);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || rows.getBoolean(1)) {
                    throw changed();
                }
            }
        }
    }

    private static void requireAdoptionAudit(Connection connection,
            RecoveryPolicyEvidence proof) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                select detail::text from audit_logs
                 where action = 'vm.recovery_adopt' and actor_role = 'ROOT_OPERATOR'
                   and target_type = 'vm' and target_id = ?
                   and detail->>'operationId' = ?
                """)) {
            statement.setString(1, proof.vmPublicId().toString());
            statement.setString(2, proof.operationId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw changed();
                }
                JsonNode detail = JSON.readTree(rows.getString(1));
                if (rows.next()
                        || !proof.manifestSha256().equals(text(detail, "manifestSha256"))
                        || !proof.restoreReceiptSha256().equals(text(detail, "restoreReceiptSha256"))
                        || !proof.source().nodePublicId().toString().equals(text(detail, "sourceNodeId"))
                        || !proof.target().nodePublicId().toString().equals(text(detail, "targetNodeId"))
                        || proof.source().vmid() != integer(detail, "sourceVmid")
                        || proof.target().vmid() != integer(detail, "targetVmid")
                        || proof.fencingToken() != integer(detail, "fencingToken")
                        || !proof.vmUpdatedAt().equals(Instant.parse(text(detail, "adoptedVmUpdatedAt")))
                        || proof.policy().revision() != integer(detail, "policyRevision")
                        || proof.policy().generation() != integer(detail, "policyDesiredGeneration")
                        || !proof.policy().desiredHash().equals(text(detail, "policyDesiredHash"))
                        || !proof.policy().appliedGeneration().equals(integer(detail,
                                "policyAppliedGeneration"))
                        || !proof.policy().appliedHash().equals(text(detail, "policyAppliedHash"))
                        || !proof.source().providerDigest().equals(text(detail, "sourceConfigDigest"))
                        || !proof.target().providerDigest().equals(text(detail, "targetConfigDigest"))
                        || proof.policy().generation() - 1 != integer(detail, "policyGenerationBefore")
                        || proof.policy().generation() != integer(detail, "policyGenerationPending")) {
                    throw changed();
                }
            }
        }
    }

    public void insertAudit(Connection connection, RecoveryPolicyEvidence proof,
            String proofSha, UUID attemptId, String action) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into audit_logs(actor_id, actor_role, action, target_type, target_id,
                                       detail, ip)
                values (null, 'ROOT_OPERATOR', ?, 'vm', ?,
                        jsonb_build_object('operationId', ?::text, 'attemptId', ?::text,
                          'manifestSha256', ?::text, 'policyProofSha256', ?::text,
                          'sourceNodeId', ?::text, 'sourceVmid', ?::integer,
                          'targetNodeId', ?::text, 'targetVmid', ?::integer,
                          'fencingToken', ?::bigint, 'generation', ?::bigint,
                          'desiredHash', ?::text), null)
                """)) {
            statement.setString(1, action);
            statement.setString(2, proof.vmPublicId().toString());
            statement.setString(3, proof.operationId().toString());
            statement.setString(4, attemptId.toString());
            statement.setString(5, proof.manifestSha256());
            statement.setString(6, proofSha);
            statement.setString(7, proof.source().nodePublicId().toString());
            statement.setInt(8, proof.source().vmid());
            statement.setString(9, proof.target().nodePublicId().toString());
            statement.setInt(10, proof.target().vmid());
            statement.setLong(11, proof.fencingToken());
            statement.setLong(12, proof.policy().generation());
            statement.setString(13, proof.policy().desiredHash());
            statement.executeUpdate();
        }
    }

    public void markApplied(Connection connection, RecoveryPolicyEvidence proof,
            Snapshot expected, VmNetworkPolicyApplyState expectedState) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update vm_network_policies
                   set applied_generation = desired_generation,
                       applied_hash = desired_hash, apply_state = 'APPLIED',
                       last_error = null, updated_at = now()
                 where vm_id = ? and revision = ? and desired_generation = ?
                   and desired_hash = ? and apply_state = ?::vm_network_policy_apply_state
                   and applied_generation = ? and applied_hash = ?
                """)) {
            statement.setLong(1, expected.target().vmId());
            statement.setLong(2, proof.policy().revision());
            statement.setLong(3, proof.policy().generation());
            statement.setString(4, proof.policy().desiredHash());
            statement.setString(5, expectedState.name());
            statement.setLong(6, proof.policy().appliedGeneration());
            statement.setString(7, proof.policy().appliedHash());
            if (statement.executeUpdate() != 1) {
                throw changed();
            }
        }
    }

    public void markFailed(Connection connection, RecoveryPolicyEvidence proof,
            Snapshot expected, boolean closed) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                update vm_network_policies
                   set apply_state = ?::vm_network_policy_apply_state,
                       last_error = '복구 정책 적용을 확인할 수 없습니다.', updated_at = now()
                 where vm_id = ? and revision = ? and desired_generation = ?
                   and desired_hash = ? and apply_state = ?::vm_network_policy_apply_state
                   and applied_generation = ? and applied_hash = ?
                """)) {
            statement.setString(1, closed ? "FAILED_CLOSED" : "FAILED");
            statement.setLong(2, expected.target().vmId());
            statement.setLong(3, proof.policy().revision());
            statement.setLong(4, proof.policy().generation());
            statement.setString(5, proof.policy().desiredHash());
            statement.setString(6, expected.state().name());
            statement.setLong(7, proof.policy().appliedGeneration());
            statement.setString(8, proof.policy().appliedHash());
            if (statement.executeUpdate() != 1) {
                throw changed();
            }
        }
    }

    private static String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || !value.isString() ? "" : value.asText();
    }

    private static long integer(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || !value.isIntegralNumber() ? Long.MIN_VALUE : value.longValue();
    }

    private static String hash(String value) {
        if (value == null) {
            throw changed();
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static IllegalStateException changed() {
        return new IllegalStateException("Pinned recovery database state changed.");
    }
}
