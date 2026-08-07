package kr.ac.pusan.pickle.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Relay sync watchdog: the never-contacted clause (an enabled relay with no
 * successful sync — token issued or not — is flagged once the first-contact
 * grace passes), the quiet states (fresh row, disabled relay, healthy
 * contact), the edge-triggered single alert, and the pre-existing
 * contact-lost clause. The sweep is invoked directly (no JobRunr server
 * needed); no VMs are created, so this suite claims no proxmox_vmid band.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class RelayContactMonitorJobTest {

    private static final AtomicInteger SOURCE_SEQ = new AtomicInteger(1);

    @Autowired
    private RelayContactMonitorJob monitorJob;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void neverContactedRelayWithATokenIsFlaggedAfterTheGrace() {
        long relayId = relay("rcm-installed", true);
        age(relayId);

        monitorJob.run();

        assertThat(contactLostSince(relayId)).isNotNull();
        assertThat(noticeCount(relayId, "relay.never_contacted")).isPositive();
        // The install-side wording: a token exists, the agent never used it.
        assertThat(jdbcTemplate.queryForObject("""
                select body from notifications
                 where event = 'relay.never_contacted'
                   and payload ->> 'relayId' = ? limit 1
                """, String.class, String.valueOf(relayId))).contains("한 번도 동기화하지");
    }

    @Test
    void tokenlessEnabledRelayIsFlaggedAfterTheGrace() {
        // The incident shape: a wiped database left the relay row without a
        // token; the agent polled into 401s for half a day while the tunnel
        // stayed green. Fails-closed auth means an enabled token-less relay is
        // guaranteed silent — that must become a finding, not stay invisible.
        long relayId = relay("rcm-tokenless", false);
        age(relayId);

        monitorJob.run();

        assertThat(contactLostSince(relayId)).isNotNull();
        assertThat(jdbcTemplate.queryForObject("""
                select body from notifications
                 where event = 'relay.never_contacted'
                   and payload ->> 'relayId' = ? limit 1
                """, String.class, String.valueOf(relayId))).contains("토큰이 발급되지 않은");
    }

    @Test
    void freshAndDisabledRelaysStayQuiet() {
        // Within the grace: the operator is still installing — normal.
        long fresh = relay("rcm-fresh", false);
        // Disabled: the documented staging state; never watched however old.
        long disabled = relay("rcm-disabled", true);
        jdbcTemplate.update("update relays set enabled = false where id = ?", disabled);
        age(disabled);

        monitorJob.run();

        assertThat(contactLostSince(fresh)).isNull();
        assertThat(contactLostSince(disabled)).isNull();
        assertThat(noticeCount(fresh, "relay.never_contacted")).isZero();
        assertThat(noticeCount(disabled, "relay.never_contacted")).isZero();
    }

    @Test
    void healthySyncKeepsTheRelayUnflagged() {
        long relayId = relay("rcm-healthy", true);
        jdbcTemplate.update("""
                update relays set last_contact_at = now(),
                       updated_at = now() - interval '20 minutes'
                 where id = ?
                """, relayId);

        monitorJob.run();

        assertThat(contactLostSince(relayId)).isNull();
        assertThat(noticeCount(relayId, "relay.never_contacted")).isZero();
        assertThat(noticeCount(relayId, "relay.contact_lost")).isZero();
    }

    @Test
    void neverContactedAlertIsEdgeTriggered() {
        long relayId = relay("rcm-edge", true);
        age(relayId);

        monitorJob.run();
        long after = noticeCount(relayId, "relay.never_contacted");
        assertThat(after).isPositive();

        // Re-run: contact_lost_since is already stamped — no second alert.
        monitorJob.run();
        assertThat(noticeCount(relayId, "relay.never_contacted")).isEqualTo(after);
    }

    @Test
    void relayThatSyncedBeforeAndWentQuietIsStillFlaggedAsLost() {
        long relayId = relay("rcm-lost", true);
        jdbcTemplate.update("""
                update relays set last_contact_at = now() - interval '10 minutes'
                 where id = ?
                """, relayId);

        monitorJob.run();

        assertThat(contactLostSince(relayId)).isNotNull();
        assertThat(noticeCount(relayId, "relay.contact_lost")).isPositive();
        assertThat(noticeCount(relayId, "relay.never_contacted")).isZero();
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    /** Enabled relay row, optionally with an issued token, never contacted. */
    private long relay(String slug, boolean withToken) {
        return jdbcTemplate.queryForObject("""
                insert into relays (name, source_ip, token_hash, port_band_start, port_band_end)
                values (?, ?, ?, 10000, 19999)
                returning id
                """, Long.class, slug + "-" + UUID.randomUUID().toString().substring(0, 8),
                "198.51.100." + SOURCE_SEQ.getAndIncrement(),
                withToken ? RelayTokens.sha256Hex("rcm-token-" + slug) : null);
    }

    /** Pushes the row's last admin-side write past the first-contact grace. */
    private void age(long relayId) {
        jdbcTemplate.update(
                "update relays set updated_at = now() - interval '20 minutes' where id = ?",
                relayId);
    }

    private java.sql.Timestamp contactLostSince(long relayId) {
        return jdbcTemplate.queryForObject(
                "select contact_lost_since from relays where id = ?", java.sql.Timestamp.class,
                relayId);
    }

    private long noticeCount(long relayId, String event) {
        return jdbcTemplate.queryForObject("""
                select count(*) from notifications
                 where event = ? and payload ->> 'relayId' = ?
                """, Long.class, event, String.valueOf(relayId));
    }
}
