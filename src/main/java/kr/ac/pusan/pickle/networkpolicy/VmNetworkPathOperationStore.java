package kr.ac.pusan.pickle.networkpolicy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.ac.pusan.pickle.relay.RelayMappingRetirementStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Durable derived-path source and recoverable consumer ordering state. */
@Repository
public class VmNetworkPathOperationStore {

    public enum OwnerKind { HTTP_ROUTE, PORT_MAPPING }
    public enum Action { OPEN, REPLACE, CLOSE, SUSPEND, RESUME, DELETE }
    public enum Phase { POLICY_ADD, CONSUMER_APPLY, CONSUMER_RETIRE, POLICY_REMOVE, DONE }

    public record Operation(UUID id, long vmId, OwnerKind ownerKind, long ownerId,
            Action action, Phase phase, Long newPathId, Long oldPathId,
            Long policyGeneration, Long consumerGeneration, UUID retirementId,
            long revision) {
    }

    private final JdbcTemplate jdbc;
    private final RelayMappingRetirementStore retirements;

    public VmNetworkPathOperationStore(JdbcTemplate jdbc,
            RelayMappingRetirementStore retirements) {
        this.jdbc = jdbc;
        this.retirements = retirements;
    }

    public boolean managed(long vmId) {
        Boolean result = jdbc.queryForObject(
                "select exists(select 1 from vm_network_policies where vm_id = ?)",
                Boolean.class, vmId);
        return Boolean.TRUE.equals(result);
    }

    @Transactional
    public boolean openHttp(long vmId, long routeId, int targetPort) {
        if (!managed(vmId)) {
            return false;
        }
        long path = addPath(vmId, OwnerKind.HTTP_ROUTE, routeId, "PROXY", "TCP", targetPort);
        requireAdded(addOperation(vmId, OwnerKind.HTTP_ROUTE, routeId, Action.OPEN,
                Phase.POLICY_ADD, path, null));
        return true;
    }

    @Transactional
    public boolean replaceHttp(long vmId, long routeId, int oldPort, int newPort) {
        if (!managed(vmId) || oldPort == newPort) {
            return false;
        }
        long oldPath = requirePath(OwnerKind.HTTP_ROUTE, routeId, "TCP", oldPort);
        long newPath = addPath(vmId, OwnerKind.HTTP_ROUTE, routeId, "PROXY", "TCP", newPort);
        requireAdded(addOperation(vmId, OwnerKind.HTTP_ROUTE, routeId, Action.REPLACE,
                Phase.POLICY_ADD, newPath, oldPath));
        return true;
    }

    @Transactional
    public boolean closeHttp(long vmId, long routeId) {
        if (!managed(vmId)) {
            return false;
        }
        Long oldPath = firstPath(OwnerKind.HTTP_ROUTE, routeId).orElse(null);
        if (oldPath == null) {
            return false;
        }
        int superseded = jdbc.update("""
                update vm_network_path_operations
                   set action = 'CLOSE', phase = 'CONSUMER_APPLY', new_path_id = null,
                       old_path_id = ?, policy_generation = null, consumer_generation = null,
                       retirement_id = null, attempts = 0, next_attempt_at = now(),
                       last_error = null, revision = revision + 1, updated_at = now()
                 where owner_kind = 'HTTP_ROUTE' and owner_id = ? and phase <> 'DONE'
                """, oldPath, routeId);
        if (superseded == 0) {
            requireAdded(addOperation(vmId, OwnerKind.HTTP_ROUTE, routeId, Action.CLOSE,
                    Phase.CONSUMER_APPLY, null, oldPath));
        }
        return true;
    }

    @Transactional
    public boolean openPort(long vmId, long mappingId, String protocol, int targetPort,
            Action action) {
        if (!managed(vmId)) {
            return false;
        }
        long path = addPath(vmId, OwnerKind.PORT_MAPPING, mappingId,
                "RELAY", protocol, targetPort);
        requireAdded(addOperation(vmId, OwnerKind.PORT_MAPPING, mappingId, action,
                Phase.POLICY_ADD, path, null));
        return true;
    }

    @Transactional
    public boolean retirePort(long vmId, long mappingId, Action action) {
        return retirePort(vmId, mappingId, action, () -> { });
    }

    /** Public fault seam is used only to hold the observed-delivery race in integration tests. */
    @Transactional
    public boolean retirePort(long vmId, long mappingId, Action action,
            Runnable afterDeliveryObserved) {
        if (!managed(vmId)) {
            return false;
        }
        Operation live = lockLive(OwnerKind.PORT_MAPPING, mappingId).orElse(null);
        String delivery = jdbc.queryForObject(
                "select delivery_state from port_mappings where id = ?",
                String.class, mappingId);
        Long path = firstPath(OwnerKind.PORT_MAPPING, mappingId).orElse(null);
        afterDeliveryObserved.run();
        if (path == null && "SUSPENDED".equals(delivery) && action == Action.DELETE) {
            if (live != null) {
                throw busyPortPath();
            }
            requireAdded(addOperation(vmId, OwnerKind.PORT_MAPPING, mappingId, action,
                    Phase.POLICY_REMOVE, null, null));
            return true;
        }
        if (path == null) {
            throw new IllegalStateException("managed port mapping의 VM derived path가 없습니다.");
        }
        if ("PENDING".equals(delivery)) {
            if (live == null || live.phase() != Phase.POLICY_ADD
                    || (live.action() != Action.OPEN && live.action() != Action.RESUME)
                    || !java.util.Objects.equals(live.newPathId(), path)) {
                throw busyPortPath();
            }
            int changed = jdbc.update("""
                    update vm_network_path_operations
                       set action = ?::vm_network_path_action, phase = 'POLICY_REMOVE',
                           new_path_id = null, old_path_id = ?, policy_generation = null,
                           consumer_generation = null, attempts = 0, next_attempt_at = now(),
                           last_error = null, revision = revision + 1, updated_at = now()
                     where id = ? and phase = 'POLICY_ADD' and revision = ?
                       and exists(select 1 from port_mappings m
                                   where m.id = ? and m.delivery_state = 'PENDING')
                    """, action.name(), path, live.id(), live.revision(), mappingId);
            if (changed != 1) {
                throw busyPortPath();
            }
        } else {
            if (live != null) {
                throw busyPortPath();
            }
            if (!addOperation(vmId, OwnerKind.PORT_MAPPING, mappingId, action,
                    Phase.CONSUMER_RETIRE, null, path)) {
                throw VmNetworkPolicyStore.unavailable(
                        "이 port mapping의 이전 공개 경로 작업이 아직 진행 중입니다.");
            }
            RelayMappingRetirementStore.Retirement retirement = retirements.begin(mappingId);
            int attached = jdbc.update("""
                    update vm_network_path_operations
                       set consumer_generation = ?, retirement_id = ?, updated_at = now()
                     where owner_kind = 'PORT_MAPPING' and owner_id = ?
                       and phase = 'CONSUMER_RETIRE'
                    """, retirement.generation(), retirement.id(), mappingId);
            if (attached != 1) {
                throw new IllegalStateException("port mapping retirement 작업을 저장하지 못했습니다.");
            }
        }
        return true;
    }

    public List<UUID> due(int limit) {
        return jdbc.queryForList("""
                select id from vm_network_path_operations
                 where phase <> 'DONE' and next_attempt_at <= now()
                 order by next_attempt_at, created_at limit ?
                """, UUID.class, limit);
    }

    public List<UUID> liveForVm(long vmId) {
        return jdbc.queryForList("""
                select id from vm_network_path_operations
                 where vm_id = ? and phase <> 'DONE' order by created_at, id
                """, UUID.class, vmId);
    }

    public Optional<Operation> find(UUID id) {
        return jdbc.query("""
                select id, vm_id, owner_kind::text, owner_id, action::text, phase::text,
                       new_path_id, old_path_id, policy_generation, consumer_generation,
                       retirement_id, revision
                  from vm_network_path_operations where id = ?
                """, rs -> rs.next() ? Optional.of(new Operation(
                        rs.getObject(1, UUID.class), rs.getLong(2), OwnerKind.valueOf(rs.getString(3)),
                        rs.getLong(4), Action.valueOf(rs.getString(5)), Phase.valueOf(rs.getString(6)),
                        rs.getObject(7, Long.class), rs.getObject(8, Long.class),
                        rs.getObject(9, Long.class), rs.getObject(10, Long.class),
                        rs.getObject(11, UUID.class), rs.getLong(12))) : Optional.empty(), id);
    }

    private Optional<Operation> lockLive(OwnerKind ownerKind, long ownerId) {
        return jdbc.query("""
                select id, vm_id, owner_kind::text, owner_id, action::text, phase::text,
                       new_path_id, old_path_id, policy_generation, consumer_generation,
                       retirement_id, revision
                  from vm_network_path_operations
                 where owner_kind = ?::vm_network_path_owner_kind and owner_id = ?
                   and phase <> 'DONE'
                 for update
                """, rs -> rs.next() ? Optional.of(new Operation(
                        rs.getObject(1, UUID.class), rs.getLong(2), OwnerKind.valueOf(rs.getString(3)),
                        rs.getLong(4), Action.valueOf(rs.getString(5)), Phase.valueOf(rs.getString(6)),
                        rs.getObject(7, Long.class), rs.getObject(8, Long.class),
                        rs.getObject(9, Long.class), rs.getObject(10, Long.class),
                        rs.getObject(11, UUID.class), rs.getLong(12))) : Optional.empty(),
                ownerKind.name(), ownerId);
    }

    public boolean move(Operation expected, Phase next, Long policyGeneration,
            Long consumerGeneration, UUID retirementId) {
        return jdbc.update("""
                update vm_network_path_operations
                   set phase = ?::vm_network_path_phase,
                       policy_generation = coalesce(?, policy_generation),
                       consumer_generation = coalesce(?, consumer_generation),
                       retirement_id = coalesce(?, retirement_id),
                       attempts = 0, next_attempt_at = now(), last_error = null,
                       revision = revision + 1, updated_at = now()
                 where id = ? and phase = ?::vm_network_path_phase and revision = ?
                """, next.name(), policyGeneration, consumerGeneration, retirementId,
                expected.id(), expected.phase().name(), expected.revision()) == 1;
    }

    /** CASes the HTTP consumer result before removing any durable allow source. */
    @Transactional
    public boolean finishHttpConsumer(Operation expected, Phase next, long consumerGeneration) {
        int moved = jdbc.update("""
                update vm_network_path_operations
                   set phase = ?::vm_network_path_phase, old_path_id = null,
                       consumer_generation = ?, attempts = 0, next_attempt_at = now(),
                       last_error = null, revision = revision + 1, updated_at = now()
                 where id = ? and phase = ?::vm_network_path_phase and revision = ?
                """, next.name(), consumerGeneration, expected.id(), expected.phase().name(),
                expected.revision());
        if (moved != 1) {
            return false;
        }
        if (expected.action() == Action.CLOSE) {
            deleteOwnerPaths(OwnerKind.HTTP_ROUTE, expected.ownerId());
        } else {
            deletePath(expected.oldPathId());
        }
        return true;
    }

    /** CASes the exact retirement receipt before removing its VM allow source. */
    @Transactional
    public boolean finishRetirement(Operation expected) {
        int moved = jdbc.update("""
                update vm_network_path_operations
                   set phase = 'POLICY_REMOVE', old_path_id = null,
                       attempts = 0, next_attempt_at = now(), last_error = null,
                       revision = revision + 1, updated_at = now()
                 where id = ? and phase = 'CONSUMER_RETIRE' and revision = ?
                """, expected.id(), expected.revision());
        if (moved != 1) {
            return false;
        }
        deletePath(expected.oldPathId());
        return true;
    }

    /** Removes a never-published pending path before applying the reduced VM policy. */
    @Transactional
    public boolean removeOldPath(Operation expected) {
        int detached = jdbc.update("""
                update vm_network_path_operations
                   set old_path_id = null, revision = revision + 1, updated_at = now()
                 where id = ? and phase = 'POLICY_REMOVE' and revision = ?
                   and old_path_id = ?
                """, expected.id(), expected.revision(), expected.oldPathId());
        if (detached != 1) {
            return false;
        }
        deletePath(expected.oldPathId());
        return true;
    }

    public void retry(UUID id, RuntimeException failure) {
        String message = failure.getMessage() == null || failure.getMessage().isBlank()
                ? "네트워크 경로 수렴 상태를 확인할 수 없습니다." : failure.getMessage();
        if (message.length() > 500) {
            message = message.substring(0, 500);
        }
        jdbc.update("""
                update vm_network_path_operations
                   set attempts = attempts + 1,
                       next_attempt_at = now() + make_interval(secs => least(300, 5 * (attempts + 1))),
                       last_error = ?, updated_at = now()
                 where id = ? and phase <> 'DONE'
                """, message, id);
    }

    public void deletePath(Long pathId) {
        if (pathId != null) {
            jdbc.update("delete from vm_network_derived_paths where id = ?", pathId);
        }
    }

    public void deleteOwnerPaths(OwnerKind ownerKind, long ownerId) {
        jdbc.update("""
                delete from vm_network_derived_paths
                 where owner_kind = ?::vm_network_path_owner_kind and owner_id = ?
                """, ownerKind.name(), ownerId);
    }

    public boolean routeConsumerReady(long routeId) {
        Boolean managed = jdbc.queryForObject("""
                select case when not exists (
                    select 1 from vm_network_policies p
                    join domains d on d.vm_id = p.vm_id
                    join routes r on r.domain_id = d.id where r.id = ?)
                  then true when not exists (
                    select 1 from vm_network_path_operations
                     where owner_kind = 'HTTP_ROUTE' and owner_id = ? and phase <> 'DONE')
                  then true else exists (
                    select 1 from vm_network_path_operations
                     where owner_kind = 'HTTP_ROUTE' and owner_id = ?
                       and phase in ('CONSUMER_APPLY','POLICY_REMOVE')) end
                """, Boolean.class, routeId, routeId, routeId);
        return Boolean.TRUE.equals(managed);
    }

    private long addPath(long vmId, OwnerKind ownerKind, long ownerId,
            String sourceKind, String protocol, int targetPort) {
        Long id = jdbc.query("""
                insert into vm_network_derived_paths
                    (vm_id, owner_kind, owner_id, source_kind, protocol, target_port)
                values (?, ?::vm_network_path_owner_kind, ?, ?::vm_network_path_source_kind, ?, ?)
                on conflict (owner_kind, owner_id, source_kind, protocol, target_port)
                do update set vm_id = excluded.vm_id
                returning id
                """, rs -> rs.next() ? rs.getLong(1) : null,
                vmId, ownerKind.name(), ownerId, sourceKind, protocol, targetPort);
        if (id == null) {
            throw new IllegalStateException("VM derived path를 저장하지 못했습니다.");
        }
        return id;
    }

    private long requirePath(OwnerKind ownerKind, long ownerId, String protocol, int port) {
        return jdbc.queryForObject("""
                select id from vm_network_derived_paths
                 where owner_kind = ?::vm_network_path_owner_kind and owner_id = ?
                   and protocol = ? and target_port = ?
                """, Long.class, ownerKind.name(), ownerId, protocol, port);
    }

    private Optional<Long> firstPath(OwnerKind ownerKind, long ownerId) {
        return jdbc.query("""
                select id from vm_network_derived_paths
                 where owner_kind = ?::vm_network_path_owner_kind and owner_id = ?
                 order by id limit 1
                """, rs -> rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(),
                ownerKind.name(), ownerId);
    }

    private boolean addOperation(long vmId, OwnerKind ownerKind, long ownerId,
            Action action, Phase phase, Long newPath, Long oldPath) {
        return jdbc.update("""
                insert into vm_network_path_operations
                    (vm_id, owner_kind, owner_id, action, phase, new_path_id, old_path_id)
                values (?, ?::vm_network_path_owner_kind, ?, ?::vm_network_path_action,
                        ?::vm_network_path_phase, ?, ?)
                on conflict (owner_kind, owner_id) where phase <> 'DONE' do nothing
                """, vmId, ownerKind.name(), ownerId, action.name(), phase.name(),
                newPath, oldPath) == 1;
    }

    private static void requireAdded(boolean added) {
        if (!added) {
            throw VmNetworkPolicyStore.unavailable(
                    "이 공개 경로의 이전 작업이 아직 진행 중입니다.");
        }
    }

    private static RuntimeException busyPortPath() {
        return VmNetworkPolicyStore.unavailable(
                "이 port mapping의 공개 경로 상태가 변경되었거나 이전 작업이 진행 중입니다.");
    }
}
