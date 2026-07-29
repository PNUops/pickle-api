package kr.ac.pusan.pickle.relay;

import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.config.RelayProperties;
import kr.ac.pusan.pickle.notification.NotificationEvent;
import kr.ac.pusan.pickle.notification.NotificationService;
import org.jobrunr.jobs.annotations.Job;
import org.jobrunr.jobs.annotations.Recurring;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Contact-lost watchdog: a relay whose agent has not synced for 3× its poll
 * interval gets {@code contact_lost_since} stamped and the sysadmins one HIGH
 * notification. <b>Edge-triggered</b>: the flag is set exactly once per
 * outage (the CAS update below is the edge) and cleared by the next
 * successful sync, so a flapping relay re-alerts per outage, not per cycle.
 * Only enabled relays with an issued token are watched — a relay that cannot
 * authenticate yet has nothing to lose contact with.
 */
@Component
public class RelayContactMonitorJob {

    public static final String JOB_ID = "relay-contact-monitor";

    private static final Logger log = LoggerFactory.getLogger(RelayContactMonitorJob.class);

    private final JdbcTemplate jdbcTemplate;
    private final RelayProperties relayProperties;
    private final NotificationService notificationService;

    public RelayContactMonitorJob(JdbcTemplate jdbcTemplate, RelayProperties relayProperties,
            NotificationService notificationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.relayProperties = relayProperties;
        this.notificationService = notificationService;
    }

    /** One watch cycle; public/argument-free for JobRunr, tests call directly. */
    @Recurring(id = JOB_ID, interval = "PT2M")
    @Job(name = JOB_ID, retries = 0)
    public void run() {
        long thresholdSeconds = 3L * relayProperties.pollIntervalSeconds();
        List<Map<String, Object>> lost = jdbcTemplate.queryForList("""
                update relays
                   set contact_lost_since = now(), updated_at = now()
                 where enabled and token_hash is not null and contact_lost_since is null
                   and last_contact_at is not null
                   and last_contact_at < now() - make_interval(secs => ?)
                returning id, name, last_contact_at
                """, thresholdSeconds);
        for (Map<String, Object> relay : lost) {
            log.warn("relay {} ({}) contact lost — last sync {}", relay.get("id"),
                    relay.get("name"), relay.get("last_contact_at"));
            notificationService.publish(notificationService.sysAdminIds(),
                    NotificationEvent.RELAY_CONTACT_LOST,
                    Map.of("relayId", relay.get("id"), "relayName", relay.get("name"),
                            "lastContactAt", String.valueOf(relay.get("last_contact_at"))),
                    null);
        }
    }
}
