package kr.ac.pusan.pickle.provisioning;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Allocates user-VM vmids from the DB-owned monotonic {@code vmid_seq}
 * (starts at 100000, the user-VM band — bands in the V50 migration header).
 * Replaces Proxmox {@code GET /cluster/nextid}, which handed out the
 * smallest free number: user VMs landed next to the infra LXCs and a
 * destroyed guest's vmid got recycled, mixing Proxmox task history across
 * unrelated VMs. A sequence value is never reused.
 */
@Component
public class VmidSequence {

    private final JdbcTemplate jdbcTemplate;

    public VmidSequence(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public int next() {
        return jdbcTemplate.queryForObject("select nextval('vmid_seq')", Integer.class);
    }
}
