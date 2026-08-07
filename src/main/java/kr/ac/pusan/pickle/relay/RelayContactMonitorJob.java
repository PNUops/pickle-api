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
 * Relay sync watchdog, covering both ways a relay can be silent:
 *
 * <ul>
 *   <li><b>Contact lost</b> — the agent synced before but has not for 3× its
 *       poll interval.</li>
 *   <li><b>Never contacted</b> — an ENABLED relay has no successful sync at
 *       all and nothing has touched its row for the first-contact grace. This
 *       deliberately includes relays whose sync token was never issued (or was
 *       wiped): the auth filter fails closed, so an agent out there polls into
 *       401s forever while the tunnel itself looks healthy — exactly the
 *       half-day silent outage a fresh database once produced. A relay that is
 *       intentionally not in service yet should be disabled; enabled is the
 *       statement that syncs are expected.</li>
 * </ul>
 *
 * Either finding stamps {@code contact_lost_since} and sends the sysadmins one
 * HIGH notification. <b>Edge-triggered</b>: the flag is set exactly once per
 * outage (the CAS updates below are the edge) and cleared by the next
 * successful sync, so a flapping relay re-alerts per outage, not per cycle.
 * Disabled relays are never watched.
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
        flagLostContact();
        flagNeverContacted();
    }

    /** Relays that synced before and went quiet for 3× their poll interval. */
    private void flagLostContact() {
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

    /**
     * Enabled relays with NO successful sync ever, once the row has sat
     * untouched past the first-contact grace. {@code updated_at} is the
     * baseline on purpose: for a never-contacted relay it is the time of the
     * last admin-side write (creation, token issue, enable), so issuing a
     * fresh token restarts the window and a just-created relay stays quiet
     * while the operator installs the agent. A failed sync attempt never
     * touches the row, which is precisely why silence here is a finding and
     * not a heartbeat gap.
     */
    private void flagNeverContacted() {
        long graceSeconds = relayProperties.firstContactGraceSeconds();
        List<Map<String, Object>> silent = jdbcTemplate.queryForList("""
                update relays
                   set contact_lost_since = now(), updated_at = now()
                 where enabled and contact_lost_since is null
                   and last_contact_at is null
                   and updated_at < now() - make_interval(secs => ?)
                returning id, name, token_hash is not null as token_issued
                """, graceSeconds);
        for (Map<String, Object> relay : silent) {
            boolean tokenIssued = Boolean.TRUE.equals(relay.get("token_issued"));
            log.warn("relay {} ({}) has never synced (token issued: {})", relay.get("id"),
                    relay.get("name"), tokenIssued);
            notificationService.publish(notificationService.sysAdminIds(),
                    NotificationEvent.RELAY_NEVER_CONTACTED,
                    Map.of("relayId", relay.get("id"), "relayName", relay.get("name"),
                            "tokenIssued", tokenIssued),
                    null);
        }
    }
}
