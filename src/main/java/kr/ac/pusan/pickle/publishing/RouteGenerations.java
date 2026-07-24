package kr.ac.pusan.pickle.publishing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Allocates the DB-owned monotonic generation token (the proxy-agent control
 * contract) from {@code route_generation_seq}. A single global sequence is monotonic
 * per-FQDN too, so a re-created route for a reused FQDN always outranks whatever
 * the agent last applied — a stale apply can never resurrect an old vhost.
 */
@Component
public class RouteGenerations {

    private final JdbcTemplate jdbcTemplate;

    public RouteGenerations(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long next() {
        return jdbcTemplate.queryForObject("select nextval('route_generation_seq')", Long.class);
    }
}
