package kr.ac.pusan.pickle.networkpolicy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.ErrorCodes;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable VM-policy opt-in, ordered rules and generation-guarded apply results. */
@Repository
public class VmNetworkPolicyStore {

    static final String FIND_SQL = """
            select p.vm_id, p.revision, p.desired_generation, p.applied_generation,
                   p.desired_hash, p.applied_hash, p.apply_state::text, p.last_error,
                   p.updated_at, r.position, r.direction, r.action, r.protocol,
                   r.peer::text, r.port_start, r.port_end
              from vm_network_policies p
              left join vm_network_policy_rules r on r.vm_id = p.vm_id
             where p.vm_id = ?
             order by r.position
            """;

    public record Snapshot(long vmId, long revision, long desiredGeneration,
            Long appliedGeneration, String desiredHash, String appliedHash,
            VmNetworkPolicyApplyState state, String lastError, Instant updatedAt,
            List<VmNetworkRule> rules) {
        public Snapshot {
            rules = List.copyOf(rules);
        }
    }

    public record Target(long vmId, long nodeId, String apiHost, String nodeName,
            int vmid, String bridge, int mtu, long allocationId, String ip, String vmStatus) {
        public VmFirewallBarrierReconciler.Target providerTarget() {
            return new VmFirewallBarrierReconciler.Target(apiHost, nodeName, vmid, bridge, mtu);
        }
    }

    private final JdbcTemplate jdbc;

    public VmNetworkPolicyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Snapshot> find(long vmId) {
        return jdbc.query(FIND_SQL, rs -> {
            if (!rs.next()) {
                return Optional.empty();
            }
            long id = rs.getLong("vm_id");
            long revision = rs.getLong("revision");
            long desiredGeneration = rs.getLong("desired_generation");
            Long appliedGeneration = rs.getObject("applied_generation", Long.class);
            String desiredHash = rs.getString("desired_hash");
            String appliedHash = rs.getString("applied_hash");
            VmNetworkPolicyApplyState state = VmNetworkPolicyApplyState.valueOf(
                    rs.getString("apply_state"));
            String lastError = rs.getString("last_error");
            Instant updatedAt = rs.getTimestamp("updated_at").toInstant();
            List<VmNetworkRule> rules = new ArrayList<>();
            do {
                if (rs.getObject("position") != null) {
                    rules.add(rule(rs));
                }
            } while (rs.next());
            return Optional.of(new Snapshot(id, revision, desiredGeneration, appliedGeneration,
                    desiredHash, appliedHash, state, lastError, updatedAt, rules));
        }, vmId);
    }

    /** Provisioning owns the first durable opt-in; public PUT never calls this. */
    public Snapshot initialize(long vmId, String desiredHash) {
        jdbc.update("""
                insert into vm_network_policies
                    (vm_id, revision, desired_generation, desired_hash, apply_state)
                values (?, 0, 1, ?, 'PENDING')
                on conflict do nothing
                """, vmId, desiredHash);
        return find(vmId).orElseThrow();
    }

    /** Derived path changes advance enforcement generation without changing user CAS revision. */
    public Snapshot refreshDerived(long vmId, String desiredHash) {
        jdbc.update("""
                update vm_network_policies
                   set desired_generation = desired_generation + 1,
                       desired_hash = ?, apply_state = 'PENDING', last_error = null,
                       updated_at = now()
                 where vm_id = ? and desired_hash <> ?
                """, desiredHash, vmId, desiredHash);
        return find(vmId).orElseThrow(VmNetworkPolicyStore::unavailable);
    }

    /** Fresh scalar target; callers separately validate the node's structured labels. */
    public Target target(long vmId, int mtu) {
        return jdbc.query("""
                select v.id, v.node_id, n.api_host, n.name, v.proxmox_vmid, n.vm_bridge,
                       v.ip_allocation_id, host(a.ip) as ip, v.status::text as vm_status
                  from vms v
                  join nodes n on n.id = v.node_id
                  join ip_allocations a on a.id = v.ip_allocation_id
                 where v.id = ? and v.proxmox_vmid is not null
                   and a.vm_id = v.id and a.status = 'ALLOCATED'
                """, rs -> {
            if (!rs.next()) {
                throw new IllegalStateException("VM 방화벽 target tuple을 확인할 수 없습니다.");
            }
            return new Target(rs.getLong("id"), rs.getLong("node_id"),
                    rs.getString("api_host"), rs.getString("name"),
                    rs.getInt("proxmox_vmid"), rs.getString("vm_bridge"), mtu,
                    rs.getLong("ip_allocation_id"), rs.getString("ip"),
                    rs.getString("vm_status"));
        }, vmId);
    }

    /** Serializes public mutation with the existing power/device claim row. */
    public long requireMutationAllowed(long vmId) {
        record MutationTarget(long nodeId, boolean allowed) {}
        MutationTarget target = jdbc.query("""
                select v.node_id, v.status <> 'CREATING'
                       and v.pending_power_action is null
                       and v.power_operation_id is null
                       and not exists (
                           select 1 from vm_power_dispatches d
                            where d.vm_id = v.id and d.terminal = false)
                  from vms v where v.id = ? for update
                """, rs -> rs.next() ? new MutationTarget(rs.getLong(1), rs.getBoolean(2)) : null,
                vmId);
        if (target == null || !target.allowed()) {
            throw unavailable("VM 생성 또는 전원·장치 작업 중에는 통신 정책을 변경할 수 없습니다."
                    + " 작성 중인 규칙은 그대로 두고 작업이 끝난 뒤 다시 시도해 주세요.");
        }
        return target.nodeId();
    }

    @Transactional
    public Snapshot replace(long vmId, long expectedRevision, List<VmNetworkRule> rules,
            String desiredHash, long actorId) {
        List<Long> generations = jdbc.query("""
                update vm_network_policies
                   set revision = revision + 1,
                       desired_generation = desired_generation + 1,
                       desired_hash = ?, apply_state = 'PENDING', last_error = null,
                       updated_by = ?, updated_at = now()
                 where vm_id = ? and revision = ?
                returning desired_generation
                """, (rs, rowNum) -> rs.getLong(1), desiredHash, actorId, vmId, expectedRevision);
        if (generations.isEmpty()) {
            if (find(vmId).isEmpty()) {
                throw unavailable();
            }
            throw new ApiException(HttpStatus.CONFLICT,
                    ErrorCodes.VM_NETWORK_POLICY_REVISION_CONFLICT,
                    "VM 통신 정책이 먼저 변경되었습니다",
                    "최신 정책을 다시 불러온 뒤 변경 내용을 확인해 주세요.");
        }
        jdbc.update("delete from vm_network_policy_rules where vm_id = ?", vmId);
        for (int position = 0; position < rules.size(); position++) {
            VmNetworkRule rule = rules.get(position);
            jdbc.update("""
                    insert into vm_network_policy_rules
                        (vm_id, position, direction, action, protocol, peer, port_start, port_end)
                    values (?, ?, ?, ?, ?, ?::cidr, ?, ?)
                    """, vmId, position, rule.direction().name(), rule.action().name(),
                    rule.protocol().name(), rule.peer().toString(), rule.portStart(), rule.portEnd());
        }
        return find(vmId).orElseThrow();
    }

    /** Publishes APPLIED only if the provider proof still belongs to this exact DB target. */
    public boolean markApplied(long vmId, long generation, String appliedHash, Target target) {
        return jdbc.update("""
                update vm_network_policies p
                   set applied_generation = ?, applied_hash = ?, apply_state = 'APPLIED',
                       last_error = null, updated_at = now()
                  from vms v, ip_allocations a, nodes n
                 where p.vm_id = ? and p.desired_generation = ? and p.desired_hash = ?
                   and v.id = p.vm_id and v.node_id = ? and v.proxmox_vmid = ?
                   and v.status::text = ? and v.ip_allocation_id = ?
                   and a.id = v.ip_allocation_id and a.vm_id = v.id
                   and a.status = 'ALLOCATED' and host(a.ip) = ?
                   and n.id = v.node_id and n.api_host = ? and n.name = ? and n.vm_bridge = ?
                   and n.labels #>> '{vm_firewall_policy,schema_version}' = '1'
                   and n.labels #>> '{vm_nic_requirements,schema_version}' = '1'
                   and (n.labels #>> '{vm_nic_requirements,mtu}')::integer = ?
                   and n.labels #>> '{vm_nic_requirements,firewall}' = 'true'
                """, generation, appliedHash, vmId, generation, appliedHash,
                target.nodeId(), target.vmid(), target.vmStatus(), target.allocationId(),
                target.ip(), target.apiHost(), target.nodeName(), target.bridge(),
                target.mtu()) == 1;
    }

    /** Short final DB fence immediately before a provider power dispatch. */
    public void requireDispatchCurrent(Target target, Snapshot policy) {
        Boolean current = jdbc.query("""
                select p.revision = ? and p.desired_generation = ? and p.desired_hash = ?
                       and p.apply_state = 'APPLIED'
                       and p.applied_generation = p.desired_generation
                       and p.applied_hash = p.desired_hash
                  from vms v
                 join vm_network_policies p on p.vm_id = v.id
                 join ip_allocations a on a.id = v.ip_allocation_id
                  join nodes n on n.id = v.node_id
                 where v.id = ? and v.node_id = ? and v.proxmox_vmid = ?
                   and v.status::text = ? and v.ip_allocation_id = ?
                   and a.vm_id = v.id and a.status = 'ALLOCATED' and host(a.ip) = ?
                   and n.api_host = ? and n.name = ? and n.vm_bridge = ?
                   and n.labels #>> '{vm_firewall_policy,schema_version}' = '1'
                   and n.labels #>> '{vm_nic_requirements,schema_version}' = '1'
                   and (n.labels #>> '{vm_nic_requirements,mtu}')::integer = ?
                   and n.labels #>> '{vm_nic_requirements,firewall}' = 'true'
                 for update of v, p, a, n
                """, rs -> rs.next() ? rs.getBoolean(1) : null,
                policy.revision(), policy.desiredGeneration(), policy.desiredHash(),
                target.vmId(), target.nodeId(), target.vmid(), target.vmStatus(),
                target.allocationId(), target.ip(), target.apiHost(), target.nodeName(),
                target.bridge(), target.mtu());
        if (!Boolean.TRUE.equals(current)) {
            throw unavailable("VM 통신 정책 또는 대상 정보가 변경되어 전원 작업을 중단했습니다.");
        }
    }

    public boolean markFailed(long vmId, long generation, VmNetworkPolicyApplyState state,
            String error) {
        if (state != VmNetworkPolicyApplyState.FAILED
                && state != VmNetworkPolicyApplyState.FAILED_CLOSED) {
            throw new IllegalArgumentException("VM policy failure state required");
        }
        return jdbc.update("""
                update vm_network_policies
                   set apply_state = ?::vm_network_policy_apply_state,
                       last_error = ?, updated_at = now()
                 where vm_id = ? and desired_generation = ?
                """, state.name(), error, vmId, generation) == 1;
    }

    /** FAILED_CLOSED is evidence about one exact provider target, never only a generation. */
    public boolean markFailedClosed(long vmId, long generation, String desiredHash,
            Target target, String error) {
        return jdbc.update("""
                update vm_network_policies p
                   set apply_state = 'FAILED_CLOSED', last_error = ?, updated_at = now()
                  from vms v, ip_allocations a, nodes n
                 where p.vm_id = ? and p.desired_generation = ? and p.desired_hash = ?
                   and v.id = p.vm_id and v.node_id = ? and v.proxmox_vmid = ?
                   and v.status::text = ? and v.ip_allocation_id = ?
                   and a.id = v.ip_allocation_id and a.vm_id = v.id
                   and a.status = 'ALLOCATED' and host(a.ip) = ?
                   and n.id = v.node_id and n.api_host = ? and n.name = ? and n.vm_bridge = ?
                   and n.labels #>> '{vm_firewall_policy,schema_version}' = '1'
                   and n.labels #>> '{vm_nic_requirements,schema_version}' = '1'
                   and (n.labels #>> '{vm_nic_requirements,mtu}')::integer = ?
                   and n.labels #>> '{vm_nic_requirements,firewall}' = 'true'
                """, error, vmId, generation, desiredHash, target.nodeId(), target.vmid(),
                target.vmStatus(), target.allocationId(), target.ip(), target.apiHost(),
                target.nodeName(), target.bridge(), target.mtu()) == 1;
    }

    public static ApiException unavailable() {
        return unavailable("이 VM은 아직 통신 정책 적용 대상으로 준비되지 않았습니다.");
    }

    public static ApiException unavailable(String detail) {
        return new ApiException(HttpStatus.CONFLICT, ErrorCodes.VM_NETWORK_POLICY_UNAVAILABLE,
                "VM 통신 정책을 사용할 수 없습니다",
                detail);
    }

    private static VmNetworkRule rule(ResultSet rs) throws SQLException {
        return new VmNetworkRule(
                VmNetworkRule.Direction.valueOf(rs.getString("direction")),
                VmNetworkRule.Action.valueOf(rs.getString("action")),
                VmNetworkRule.Protocol.valueOf(rs.getString("protocol")),
                CidrBlock.parse(rs.getString("peer")),
                rs.getObject("port_start", Integer.class),
                rs.getObject("port_end", Integer.class));
    }
}
