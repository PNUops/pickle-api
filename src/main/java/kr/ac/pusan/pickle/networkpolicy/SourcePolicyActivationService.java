package kr.ac.pusan.pickle.networkpolicy;

import java.util.List;
import kr.ac.pusan.pickle.config.NetworkPolicyProperties;
import kr.ac.pusan.pickle.publishing.Route;
import kr.ac.pusan.pickle.publishing.RouteGenerations;
import kr.ac.pusan.pickle.publishing.RouteRepository;
import kr.ac.pusan.pickle.publishing.RouteStatus;
import kr.ac.pusan.pickle.relay.RelayGenerations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Arms legacy desired-state rows only in an environment where source policy is enabled. */
@Service
public class SourcePolicyActivationService {

    private final NetworkPolicyProperties properties;
    private final JdbcTemplate jdbc;
    private final RouteRepository routes;
    private final RouteGenerations routeGenerations;
    private final RelayGenerations relayGenerations;

    public SourcePolicyActivationService(NetworkPolicyProperties properties, JdbcTemplate jdbc,
            RouteRepository routes, RouteGenerations routeGenerations,
            RelayGenerations relayGenerations) {
        this.properties = properties;
        this.jdbc = jdbc;
        this.routes = routes;
        this.routeGenerations = routeGenerations;
        this.relayGenerations = relayGenerations;
    }

    /** Marks live HTTP routes PENDING before their default empty policy can read as applied. */
    @Transactional
    public int activateRoutes() {
        if (!properties.enabled()) {
            return 0;
        }
        List<Long> ids = jdbc.queryForList("""
                select id from routes
                 where source_policy_generation is null and status <> 'REMOVED'
                 order by id limit 100
                """, Long.class);
        int activated = 0;
        for (long id : ids) {
            Route route = routes.findByIdForApply(id).orElse(null);
            if (route == null || route.getStatus() == RouteStatus.REMOVED
                    || route.getSourcePolicyGeneration() != null) {
                continue;
            }
            long generation = routeGenerations.next();
            route.setGeneration(generation);
            route.setSourcePolicyGeneration(generation);
            route.setStatus(RouteStatus.PENDING);
            route.setLastError(null);
            activated++;
        }
        return activated;
    }

    /** Arms every active mapping of this relay under the relay row's existing lock order. */
    @Transactional
    public int activateRelay(long relayId) {
        if (!properties.enabled()) {
            return 0;
        }
        Integer pending = jdbc.queryForObject("""
                select count(*) from port_mappings
                 where relay_id = ? and status = 'ACTIVE' and source_policy_generation is null
                """, Integer.class, relayId);
        if (pending == null || pending == 0) {
            return 0;
        }
        long generation = relayGenerations.bump(relayId);
        return jdbc.update("""
                update port_mappings
                   set source_policy_generation = ?, last_change_generation = ?, updated_at = now()
                 where relay_id = ? and status = 'ACTIVE' and source_policy_generation is null
                """, generation, generation, relayId);
    }

}
