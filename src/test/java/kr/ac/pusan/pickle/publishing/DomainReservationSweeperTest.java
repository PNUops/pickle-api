package kr.ac.pusan.pickle.publishing;

import kr.ac.pusan.pickle.support.RequestFixtures;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.support.AccessGrantFixtures;
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
 * Released-name reservation sweep: a released platform subdomain survives the
 * grace period and is reclaimed after it, a legacy custom leftover (released_at
 * backfilled by migration) is reclaimed with no grace, a row that still serves
 * is never touched, and the 7-days-ahead notice fires once per release
 * (re-releasing re-arms it) and is skipped entirely under a short grace.
 * The JobRunr server is off; the sweep is invoked directly.
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class DomainReservationSweeperTest {

    // Every suite in the shared embedded PG needs its OWN proxmox_vmid base
    // (vms_proxmox_vmid_active_uq is global): pick an unused range by grepping
    // VMID_SEQ across src/test before adding one.
    private static final AtomicInteger VMID_SEQ = new AtomicInteger(907_000);

    @Autowired
    private DomainReservationSweeper sweeper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private long orgId;
    private long imageId;
    private long nodeId;
    private long workspaceId;
    private long ownerId;
    private long editorId;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        String slug = "dres-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
        ownerId = createUser("owner." + slug + "@pusan.ac.kr");
        editorId = createUser("manager." + slug + "@pusan.ac.kr");
        addMember(ownerId, "OWNER");
        // The second account is an editor OF THE VM, not of the workspace: the
        // rung that makes someone hear about the VM's names now lives on the
        // access list, which createVm() writes.
        addMember(editorId, "MEMBER");
    }

    @Test
    void reservedPlatformRowSurvivesTheGraceAndIsReclaimedAfterIt() {
        long vmId = createVm();
        long fresh = platformDomain(vmId, uniqueFqdn("keep"), daysAgo(10));
        long due = platformDomain(vmId, uniqueFqdn("due"), daysAgo(31));

        sweeper.sweep();

        // 10 of 30 grace days passed → the name is still held.
        assertThat(domainStatus(fresh)).isEqualTo("ACTIVE");
        // 31 days → reclaimed, and the owners are told the name is gone. The
        // release stamp goes with the claim: a REMOVED row reserves nothing,
        // and a surviving stamp would keep it reading as "reserved".
        assertThat(domainStatus(due)).isEqualTo("REMOVED");
        assertThat(releasedAt(due)).isNull();
        assertThat(noticeCount("domain.reserve.released", due)).isEqualTo(2);

        // Re-run: reclaim already done, dedup keeps the notice single.
        sweeper.sweep();
        assertThat(noticeCount("domain.reserve.released", due)).isEqualTo(2);
    }

    @Test
    void legacyCustomLeftoverIsReclaimedImmediatelyWithCertsRevoked() {
        // The migration backfilled released_at onto old custom rows that were
        // kept alive only for their verification state; the current policy
        // never holds a custom name after release, so no grace applies.
        long vmId = createVm();
        long domainId = jdbcTemplate.queryForObject("""
                insert into domains (vm_id, kind, fqdn, verification_token, status, released_at)
                values (?, 'CUSTOM'::domain_kind, ?, 'pv-test', 'PENDING'::domain_status, ?)
                returning id
                """, Long.class, vmId,
                "legacy-" + UUID.randomUUID().toString().substring(0, 8) + ".example.com",
                Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));
        jdbcTemplate.update("""
                insert into certificates (domain_id, kind, scope, status)
                values (?, 'LETS_ENCRYPT'::certificate_kind, 'legacy', 'ACTIVE'::certificate_status)
                """, domainId);

        sweeper.sweep();

        assertThat(domainStatus(domainId)).isEqualTo("REMOVED");
        assertThat(releasedAt(domainId)).isNull();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from certificates where domain_id = ? and status <> 'REVOKED'
                """, Long.class, domainId)).isZero();
        // A custom leftover was never "reserved" — no reservation notices.
        assertThat(noticeCount("domain.reserve.released", domainId)).isZero();
    }

    @Test
    void rowWithALiveRouteIsNeverReclaimed() {
        // released_at alongside a live route is an inconsistency (revive clears
        // the stamp) — the sweep must skip it rather than yank a serving vhost.
        long vmId = createVm();
        long domainId = platformDomain(vmId, uniqueFqdn("live"), daysAgo(90));
        jdbcTemplate.update("""
                update routes set status = 'PENDING'::route_status
                 where domain_id = ?
                """, domainId);

        sweeper.sweep();

        assertThat(domainStatus(domainId)).isEqualTo("ACTIVE");
        assertThat(noticeCount("domain.reserve.released", domainId)).isZero();
    }

    @Test
    void expiryNoticeFiresOncePerReleaseAndReArmsOnReRelease() {
        long vmId = createVm();
        // 25 of 30 days passed → expiry within the 7-day notice window.
        long domainId = platformDomain(vmId, uniqueFqdn("notice"), daysAgo(25));

        sweeper.sweep();
        assertThat(noticeCount("domain.reserve.expiring", domainId)).isEqualTo(2);
        assertThat(domainStatus(domainId)).isEqualTo("ACTIVE");

        // Hourly re-run: the dedup key (domain + release stamp) absorbs it.
        sweeper.sweep();
        assertThat(noticeCount("domain.reserve.expiring", domainId)).isEqualTo(2);

        // Revived and released again → a NEW release stamp → a fresh notice.
        jdbcTemplate.update("update domains set released_at = ? where id = ?",
                Timestamp.from(daysAgo(26)), domainId);
        sweeper.sweep();
        assertThat(noticeCount("domain.reserve.expiring", domainId)).isEqualTo(4);
    }

    @Test
    void graceOfSevenDaysOrLessSkipsTheAdvanceNotice() {
        jdbcTemplate.update("""
                insert into settings (key, value) values ('platform_subdomain_reserve_days', '5'::jsonb)
                on conflict (key) do update set value = excluded.value
                """);
        try {
            long vmId = createVm();
            // Released just now: with a 5-day grace the whole window is inside
            // the notice horizon — the notice would fire immediately and say
            // nothing useful, so it is skipped.
            long domainId = platformDomain(vmId, uniqueFqdn("short"), Instant.now());

            sweeper.sweep();

            assertThat(noticeCount("domain.reserve.expiring", domainId)).isZero();
            assertThat(domainStatus(domainId)).isEqualTo("ACTIVE");
        } finally {
            jdbcTemplate.update(
                    "delete from settings where key = 'platform_subdomain_reserve_days'");
        }
    }

    @Test
    void aReviveCommittingAtTheExpiryBoundaryBeatsTheSweep() throws Exception {
        long vmId = createVm();
        long domainId = platformDomain(vmId, uniqueFqdn("race"), daysAgo(31));

        // A revive transaction holds the domain row while the sweep runs: the
        // sweep's scan snapshots the row as expired, then its reclaim must
        // block on the lock and RE-READ the row after the revive commits.
        // Flushing the scan snapshot instead would erase a domain whose revive
        // already returned success to the user.
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> sweepRun = transactionTemplate.execute(tx -> {
                jdbcTemplate.queryForObject(
                        "select id from domains where id = ? for update", Long.class, domainId);
                java.util.concurrent.Future<?> run = pool.submit(sweeper::sweep);
                try {
                    // Let the sweep pass its (unlocked) scan and pile up on the
                    // row lock; the scan itself takes only milliseconds.
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // The revive: name back in service, fresh live route.
                jdbcTemplate.update(
                        "update domains set released_at = null where id = ?", domainId);
                jdbcTemplate.update("""
                        update routes set status = 'PENDING'::route_status
                         where domain_id = ?
                        """, domainId);
                return run;
            });
            sweepRun.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(domainStatus(domainId)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "select released_at from domains where id = ?", Timestamp.class, domainId))
                .isNull();
        assertThat(noticeCount("domain.reserve.released", domainId)).isZero();
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    private long createUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", "예약테스트");
            user.setRole(UserRole.USER);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        }).getId();
    }

    private void addMember(long userId, String role) {
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, ?::workspace_member_role)
                """, workspaceId, userId, role);
    }

    private long createVm() {
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, ownerId, "예약 테스트", imageId, 1, 1024, 10);
        String hostname = "dres-vm-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, 'RUNNING'::vm_status)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                imageId, VMID_SEQ.incrementAndGet());
        // Reservation notices go to whoever the access list makes responsible
        // for the VM, so the two expected recipients have to be named there —
        // the requester as the owner approval would have made them, and the
        // second account as its editor.
        AccessGrantFixtures.grantVmToUser(jdbcTemplate, vmId, ownerId, "OWNER");
        AccessGrantFixtures.grantVmToUser(jdbcTemplate, vmId, editorId, "EDITOR");
        return vmId;
    }

    /** A released platform subdomain with its (already removed) route. */
    private long platformDomain(long vmId, String fqdn, Instant releasedAt) {
        long domainId = jdbcTemplate.queryForObject("""
                insert into domains (vm_id, kind, fqdn, root_domain, status, released_at)
                values (?, 'PLATFORM'::domain_kind, ?, 'pusan.dev', 'ACTIVE'::domain_status, ?)
                returning id
                """, Long.class, vmId, fqdn, Timestamp.from(releasedAt));
        jdbcTemplate.update("""
                insert into routes (domain_id, target_port, status, generation)
                values (?, 8080, 'REMOVED'::route_status, 1)
                """, domainId);
        return domainId;
    }

    private static Instant daysAgo(int days) {
        return Instant.now().minus(days, ChronoUnit.DAYS);
    }

    private static String uniqueFqdn(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + ".pusan.dev";
    }

    private String domainStatus(long domainId) {
        return jdbcTemplate.queryForObject("select status from domains where id = ?",
                String.class, domainId);
    }

    private Timestamp releasedAt(long domainId) {
        return jdbcTemplate.queryForObject("select released_at from domains where id = ?",
                Timestamp.class, domainId);
    }

    private long noticeCount(String event, long domainId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from notifications
                 where event = ? and payload ->> 'fqdn' =
                       (select fqdn from domains where id = ?)
                """, Long.class, event, domainId);
    }
}
