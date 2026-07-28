package kr.ac.pusan.pickle.relay;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>{@code MANDATORY} propagation: this participates in the CALLER's
 * transaction by contract — mapping delete, generation bump and the IP
 * release/clear must commit or roll back as one unit. Callers without a
 * transaction must open one (the deletion pipelines wrap the release step in
 * a {@code TransactionTemplate}).</p>
 */
@Service
public class PortMappingTeardownService {

    private static final Logger log = LoggerFactory.getLogger(PortMappingTeardownService.class);

    private final JdbcTemplate jdbcTemplate;
    private final RelayGenerations relayGenerations;

    public PortMappingTeardownService(JdbcTemplate jdbcTemplate,
            RelayGenerations relayGenerations) {
        this.jdbcTemplate = jdbcTemplate;
        this.relayGenerations = relayGenerations;
    }

    /** Deletes all of the VM's mappings, bumping each affected relay. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteMappingsForVm(long vmId) {
        List<Long> relayIds = jdbcTemplate.queryForList(
                "select distinct relay_id from port_mappings where vm_id = ?", Long.class, vmId);
        if (relayIds.isEmpty()) {
            return;
        }
        // Bump first: locks each relay row, serializing against concurrent
        // allocations before the rows disappear.
        for (Long relayId : relayIds) {
            relayGenerations.bump(relayId);
        }
        int deleted = jdbcTemplate.update("delete from port_mappings where vm_id = ?", vmId);
        log.info("vm {} teardown: removed {} port mapping(s) across {} relay(s)",
                vmId, deleted, relayIds.size());
    }
}
