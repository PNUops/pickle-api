package kr.ac.pusan.pickle.relay;

import java.util.List;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPathCoordinatorJob;
import kr.ac.pusan.pickle.networkpolicy.VmNetworkPathOperationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes a VM's port mappings when the VM (or its IP) goes away.
 *
 * <p><b>Invariant: no orphan mapping may survive its target's IP release.</b>
 * A mapping outliving the release would keep a relay DNAT pointing at an
 * address the quarantine can re-assign to ANOTHER tenant — public traffic
 * delivered to the wrong VM. The snapshot read resolves the target live (a
 * released IP already drops out of the snapshot), but the row itself must die
 * with the release so the port frees up and nothing can resurrect the rule.</p>
 *
 * <p>Legacy rows are still removed in the caller's transaction. Managed rows
 * first become durable retirement tombstones; the deletion pipeline retries
 * until the consumer returns an exact CLEARED receipt and the VM allow has
 * been removed, then releases the IP in its own short transaction.</p>
 */
@Service
public class PortMappingTeardownService {

    private static final Logger log = LoggerFactory.getLogger(PortMappingTeardownService.class);

    private final JdbcTemplate jdbcTemplate;
    private final RelayGenerations relayGenerations;
    private final VmNetworkPathOperationStore networkPaths;
    private final VmNetworkPathCoordinatorJob networkCoordinator;
    private final TransactionTemplate transactions;

    public PortMappingTeardownService(JdbcTemplate jdbcTemplate,
            RelayGenerations relayGenerations, VmNetworkPathOperationStore networkPaths,
            VmNetworkPathCoordinatorJob networkCoordinator, TransactionTemplate transactions) {
        this.jdbcTemplate = jdbcTemplate;
        this.relayGenerations = relayGenerations;
        this.networkPaths = networkPaths;
        this.networkCoordinator = networkCoordinator;
        this.transactions = transactions;
    }

    /** Begins durable retirement, then refuses IP release until every exact receipt settles. */
    public void prepareForIpRelease(long vmId) {
        transactions.executeWithoutResult(ignored -> deleteMappingsForVm(vmId));
        networkCoordinator.requireVmRetired(vmId);
    }

    /** Starts managed retirement and immediately deletes only rowless legacy mappings. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteMappingsForVm(long vmId) {
        List<Long> managed = jdbcTemplate.queryForList("""
                select id from port_mappings
                 where vm_id = ? and delivery_state <> 'LEGACY' and status <> 'REMOVING'
                """, Long.class, vmId);
        for (Long mappingId : managed) {
            if (networkPaths.retirePort(vmId, mappingId,
                    VmNetworkPathOperationStore.Action.DELETE)) {
                jdbcTemplate.update("""
                        update port_mappings set status = 'REMOVING', updated_at = now()
                         where id = ?
                        """, mappingId);
            }
        }
        List<Long> relayIds = jdbcTemplate.queryForList(
                "select distinct relay_id from port_mappings where vm_id = ? and delivery_state = 'LEGACY'",
                Long.class, vmId);
        if (relayIds.isEmpty()) {
            return;
        }
        // Bump first: locks each relay row, serializing against concurrent
        // allocations before the rows disappear.
        for (Long relayId : relayIds) {
            relayGenerations.bump(relayId);
        }
        int deleted = jdbcTemplate.update(
                "delete from port_mappings where vm_id = ? and delivery_state = 'LEGACY'", vmId);
        log.info("vm {} teardown: removed {} port mapping(s) across {} relay(s)",
                vmId, deleted, relayIds.size());
    }
}
