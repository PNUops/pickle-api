package kr.ac.pusan.pickle.relay;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Commits the exact capabilities from each current sync even if snapshot delivery is refused. */
@Service
public class RelayCapabilityObservationService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public RelayCapabilityObservationService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void observe(long relayId, List<String> capabilities) {
        List<String> current = capabilities == null ? List.of() : List.copyOf(capabilities);
        jdbc.update("""
                update relays set capabilities = ?::jsonb, capabilities_observed_at = now(),
                    updated_at = now() where id = ?
                """, objectMapper.writeValueAsString(current), relayId);
    }
}
