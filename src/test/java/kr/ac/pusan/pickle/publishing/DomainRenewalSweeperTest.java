package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.publishing.DomainRecordPolicy.DesiredSet;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordType;
import kr.ac.pusan.pickle.publishing.dns.RecordingDnsRecordProvider;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The renewal deadline: the notices before it, and what a name that passes it
 * loses. The JobRunr server is off; the sweep is invoked directly.
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, DomainRecordsTest.ZoneConfig.class})
class DomainRenewalSweeperTest {

    private static final String PUBLIC_V4 = "93.184.216.34";

    @Autowired
    private DomainRenewalSweeper sweeper;
    @Autowired
    private DomainReservationSweeper reservationSweeper;
    @Autowired
    private DomainRecordsService records;
    @Autowired
    private DomainRecordApplyJob applyJob;
    @Autowired
    private DomainRepository domainRepository;
    @Autowired
    private DomainRenewalPolicy renewalPolicy;
    @Autowired
    private RecordingDnsRecordProvider zone;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;

    private long orgId;
    private long workspaceId;

    @BeforeEach
    void setUp() {
        zone.reset();
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        String slug = "drn-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
        long ownerId = createUser("owner." + slug + "@pusan.ac.kr");
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, 'OWNER')
                """, workspaceId, ownerId);
    }

    @Test
    void aNameWhoseDeadlinePassedLosesItsRecordsAndKeepsItsReservation() {
        Domain domain = external("lapse", daysFromNow(-1));
        records.replace(domain, List.of(set("", DnsRecordType.A, PUBLIC_V4)));
        applyJob.apply(domain.getId());
        assertThat(zone.has(domain.getFqdn(), "A")).isTrue();

        sweeper.sweep();
        applyJob.apply(domain.getId());

        // Released, not removed: the owner can still take the name back for
        // the length of the reservation grace, and only the reservation sweep
        // frees it afterwards.
        assertThat(zone.has(domain.getFqdn(), "A")).isFalse();
        assertThat(releasedAt(domain.getId())).isNotNull();
        assertThat(status(domain.getId())).isEqualTo("ACTIVE");
        assertThat(noticeCount("domain.renewal.lapsed", domain.getId())).isEqualTo(1);
    }

    @Test
    void aDeadlineComingUpIsNoticedOnceAtItsNearestStage() {
        Domain domain = external("notice", daysFromNow(5));

        sweeper.sweep();
        sweeper.sweep();

        // Two sweeps, one notice: the stage is in the dedup key and the day
        // did not move between them.
        assertThat(noticeCount("domain.renewal.due", domain.getId())).isEqualTo(1);
        assertThat(releasedAt(domain.getId())).isNull();
    }

    @Test
    void renewingReArmsTheNoticeAndMovesTheDeadline() {
        Domain domain = external("renew", daysFromNow(5));
        sweeper.sweep();
        assertThat(noticeCount("domain.renewal.due", domain.getId())).isEqualTo(1);

        // A renewal is a fresh period from now, not an extension of what was
        // left, and every stage arms again against the new deadline.
        Instant renewed = renewalPolicy.deadlineFrom(Instant.now());
        setDeadline(domain.getId(), renewed);
        sweeper.sweep();
        assertThat(noticeCount("domain.renewal.due", domain.getId())).isEqualTo(1);

        setDeadline(domain.getId(), daysFromNow(5));
        sweeper.sweep();
        assertThat(noticeCount("domain.renewal.due", domain.getId())).isEqualTo(2);
    }

    @Test
    void aDeadlineFurtherOutThanEveryStageIsLeftAlone() {
        Domain domain = external("quiet", daysFromNow(120));

        sweeper.sweep();

        assertThat(noticeCount("domain.renewal.due", domain.getId())).isZero();
    }

    @Test
    void aNameAlreadyReleasedIsNotLapsedAgain() {
        Domain domain = external("released", daysFromNow(-1));
        jdbcTemplate.update("update domains set released_at = now() where id = ?",
                domain.getId());

        sweeper.sweep();

        // Its reservation grace decides it from here; a second release stamp
        // would restart that grace every night and the name would never free.
        assertThat(noticeCount("domain.renewal.lapsed", domain.getId())).isZero();
    }

    @Test
    void aLapsedNameIsFreedOnlyOnceItsRecordsAreOutOfTheZone() {
        Domain domain = external("reclaim", daysFromNow(-1));
        records.replace(domain, List.of(set("", DnsRecordType.A, PUBLIC_V4)));
        applyJob.apply(domain.getId());
        sweeper.sweep();
        // Released, and the removal has not run: the grace is over but the set
        // is still in the zone.
        setReleasedAt(domain.getId(), daysFromNow(-40));
        zone.failWith("zone unreachable");

        reservationSweeper.sweep();

        // Still reserved. Freeing it here would hand the next holder of the
        // name the last one's DNS, which is the whole reason the reclaim waits
        // on the zone rather than on the row.
        assertThat(status(domain.getId())).isEqualTo("ACTIVE");
        assertThat(zone.has(domain.getFqdn(), "A")).isTrue();

        zone.failWith(null);
        reservationSweeper.sweep();

        assertThat(status(domain.getId())).isEqualTo("REMOVED");
        assertThat(zone.has(domain.getFqdn(), "A")).isFalse();
    }

    @Test
    void aNameIsNotFreedWhileTheZoneStillHoldsSomethingUnderIt() {
        Domain domain = external("stranded", daysFromNow(-1));
        // The state a crash between the zone write and its bookkeeping leaves:
        // the set is in the zone and no row remembers owing it, because the
        // edit that dropped it deleted the row that looked never-applied.
        zone.seed(domain.getFqdn(), "A", List.of(PUBLIC_V4));
        sweeper.sweep();
        setReleasedAt(domain.getId(), daysFromNow(-40));

        reservationSweeper.sweep();

        // Freed here, the next holder of the name would inherit a record
        // pointing at the last one's server.
        assertThat(status(domain.getId())).isEqualTo("REMOVED");
        assertThat(zone.has(domain.getFqdn(), "A")).isFalse();
    }

    @Test
    void aNameStaysReservedWhenTheZoneCannotBeCleared() {
        Domain domain = external("unclearable", daysFromNow(-1));
        zone.seed(domain.getFqdn(), "A", List.of(PUBLIC_V4));
        sweeper.sweep();
        setReleasedAt(domain.getId(), daysFromNow(-40));
        zone.failWith("zone unreachable");

        reservationSweeper.sweep();

        // Reserved another cycle rather than freed with the record standing.
        assertThat(status(domain.getId())).isEqualTo("ACTIVE");
    }

    private static DesiredSet set(String name, DnsRecordType type, String value) {
        return new DesiredSet(name, type, List.of(value), 300);
    }

    private static Instant daysFromNow(int days) {
        return Instant.now().plus(days, ChronoUnit.DAYS);
    }

    private void setReleasedAt(long domainId, Instant releasedAt) {
        jdbcTemplate.update("update domains set released_at = ? where id = ?",
                Timestamp.from(releasedAt), domainId);
    }

    private void setDeadline(long domainId, Instant deadline) {
        jdbcTemplate.update("update domains set renew_due_at = ? where id = ?",
                Timestamp.from(deadline), domainId);
    }

    private Timestamp releasedAt(long domainId) {
        return jdbcTemplate.queryForObject("select released_at from domains where id = ?",
                Timestamp.class, domainId);
    }

    private String status(long domainId) {
        return jdbcTemplate.queryForObject("select status from domains where id = ?",
                String.class, domainId);
    }

    private long noticeCount(String event, long domainId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from notifications
                 where event = ? and payload ->> 'fqdn' =
                       (select fqdn from domains where id = ?)
                """, Long.class, event, domainId);
    }

    private long createUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", "연장테스트");
            user.setRole(UserRole.USER);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        }).getId();
    }

    private Domain external(String label, Instant renewDueAt) {
        String fqdn = label + "-" + UUID.randomUUID().toString().substring(0, 8) + ".pusan.dev";
        long id = jdbcTemplate.queryForObject("""
                insert into domains (workspace_id, org_id, kind, fqdn, root_domain, status,
                                     renew_due_at)
                values (?, ?, 'EXTERNAL'::domain_kind, ?, 'pusan.dev', 'ACTIVE'::domain_status, ?)
                returning id
                """, Long.class, workspaceId, orgId, fqdn, Timestamp.from(renewDueAt));
        return domainRepository.findById(id).orElseThrow();
    }
}
