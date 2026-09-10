package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.config.DnsProperties;
import kr.ac.pusan.pickle.publishing.DomainRecordPolicy.DesiredSet;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordType;
import kr.ac.pusan.pickle.publishing.dns.RecordingDnsRecordProvider;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The pass that finishes what an apply left owed, and reports what the zone
 * holds under an external name that no row claims.
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, DomainRecordsTest.ZoneConfig.class})
class DomainRecordsReconcilerTest {

    private static final String PUBLIC_V4 = "93.184.216.34";

    @Autowired
    private DomainRecordsService records;
    @Autowired
    private DomainRecordsReconciler reconciler;
    @Autowired
    private DomainRecordApplyJob applyJob;
    @Autowired
    private DomainRepository domainRepository;
    @Autowired
    private RecordingDnsRecordProvider zone;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DnsProperties dnsProperties;
    @Autowired
    private SettingsService settingsService;
    @Autowired
    private DomainRecordRepository recordRepository;
    @Autowired
    private DnsNameLocks nameLocks;
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    private long orgId;
    private long workspaceId;

    @BeforeEach
    void setUp() {
        zone.reset();
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, "drr-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void aSetLeftOwedByAFailedPushIsPushedAgain() {
        Domain domain = external("owed");
        zone.failWith("zone unreachable");
        records.replace(domain, List.of(set("", DnsRecordType.A, PUBLIC_V4)));
        applyJob.apply(domain.getId());
        assertThat(zone.has(domain.getFqdn(), "A")).isFalse();

        zone.failWith(null);
        reconciler.reconcile();

        // Nothing else comes back for these: the domain has no route, so the
        // resync's manifest cannot see it.
        assertThat(zone.recordSet(domain.getFqdn(), DnsRecordType.A).values())
                .containsExactly(PUBLIC_V4);
        assertThat(records.list(domain.getId()))
                .allMatch(r -> r.getStatus() == DomainRecordStatus.APPLIED);
    }

    @Test
    void aDomainWhoseSetsAreAllAppliedSpendsNoZoneWrite() {
        Domain domain = external("settled");
        records.replace(domain, List.of(set("", DnsRecordType.A, PUBLIC_V4)));
        applyJob.apply(domain.getId());
        long before = writes();

        reconciler.reconcile();

        // A pass over a settled zone reads and writes nothing. Every resync
        // runs this, so a write per set here would be a write per set per
        // resync against records that are already right.
        assertThat(writes()).isEqualTo(before);
    }

    /** Calls that changed the zone, as opposed to the listing the pass reads. */
    private long writes() {
        return zone.calls().stream()
                .filter(call -> call.startsWith("ensure:") || call.startsWith("remove:"))
                .count();
    }

    @Test
    void aSetNoRowClaimsIsReportedAndLeftWhenPruningIsOff() {
        Domain domain = external("orphan");
        // The shape a crash between the zone write and its bookkeeping leaves:
        // the set is in the zone and the next edit drops the row that owed it.
        zone.seed("www." + domain.getFqdn(), "A", List.of(PUBLIC_V4));

        List<DomainRecordsReconciler.Reconciliation> passes = reconciler.reconcile();

        assertThat(passes).anySatisfy(pass ->
                assertThat(pass.orphansLeft()).contains("A www." + domain.getFqdn()));
        assertThat(zone.has("www." + domain.getFqdn(), "A")).isTrue();
    }

    @Test
    void aSetNoRowClaimsIsPrunedWhenPruningIsOn() {
        Domain domain = external("prunes");
        zone.seed("www." + domain.getFqdn(), "A", List.of(PUBLIC_V4));

        reconcileWithPruning();

        assertThat(zone.has("www." + domain.getFqdn(), "A")).isFalse();
    }

    @Test
    void aClaimedSetSurvivesThePrune() {
        Domain domain = external("claimed");
        records.replace(domain, List.of(set("www", DnsRecordType.A, PUBLIC_V4)));
        applyJob.apply(domain.getId());

        reconcileWithPruning();

        assertThat(zone.recordSet("www." + domain.getFqdn(), DnsRecordType.A).values())
                .containsExactly(PUBLIC_V4);
    }

    @Test
    void thePruneStaysInsideTheNamesItOwns() {
        Domain domain = external("scoped");
        // The apex's own records, a platform subdomain, and a name that merely
        // ends in the same text without being under the domain.
        zone.seed("pusan.dev", "TXT", List.of("\"v=spf1 -all\""));
        zone.seed("someone.pusan.dev", "A", List.of("164.125.249.87"));
        zone.seed("not-" + domain.getFqdn(), "A", List.of(PUBLIC_V4));

        reconcileWithPruning();

        assertThat(zone.has("pusan.dev", "TXT")).isTrue();
        assertThat(zone.has("someone.pusan.dev", "A")).isTrue();
        assertThat(zone.has("not-" + domain.getFqdn(), "A")).isTrue();
    }

    @Test
    void aRetiredDomainWhoseRemovalFailedIsStillComeBackFor() {
        Domain domain = external("retired");
        records.replace(domain, List.of(set("", DnsRecordType.A, PUBLIC_V4)));
        applyJob.apply(domain.getId());
        // The shape an admin takedown leaves: the sets are marked for removal
        // and the name is freed in the same transaction, so the push is the
        // only thing standing between the zone and a record on a name anybody
        // can now register — and here it fails.
        zone.failWith("zone unreachable");
        records.removeAll(domain.getId());
        applyJob.apply(domain.getId());
        retire(domain.getId());
        assertThat(zone.has(domain.getFqdn(), "A")).isTrue();

        zone.failWith(null);
        reconciler.reconcile();

        // Every scan that starts from the domains table is blind to a retired
        // row. The records themselves are what remember the debt.
        assertThat(zone.has(domain.getFqdn(), "A")).isFalse();
    }

    @Test
    void aRetiredDomainWhoseNameSomebodyElseTookWritesNothing() {
        Domain domain = external("handed-on");
        records.replace(domain, List.of(set("", DnsRecordType.A, PUBLIC_V4)));
        // Applied first, on purpose: a set that never reached the zone is
        // dropped rather than queued, so only an applied one leaves a row
        // still owing a removal after the takedown.
        applyJob.apply(domain.getId());
        zone.failWith("zone unreachable");
        records.removeAll(domain.getId());
        applyJob.apply(domain.getId());
        String fqdn = domain.getFqdn();
        retire(domain.getId());
        // The name went back into the pool and the next owner took it.
        Domain next = external("handed-on-next");
        jdbcTemplate.update("update domains set fqdn = ? where id = ?", fqdn, next.getId());
        zone.failWith(null);
        zone.seed(fqdn, "A", List.of("93.184.216.35"));

        reconciler.reconcile();

        // Removing here would delete the new owner's record. The old rows are
        // forgotten instead.
        assertThat(zone.recordSet(fqdn, DnsRecordType.A).values())
                .containsExactly("93.184.216.35");
    }

    /** Frees the name the way an administrator's takedown does. */
    private void retire(long domainId) {
        jdbcTemplate.update(
                "update domains set status = 'REMOVED'::domain_status, released_at = null"
                        + " where id = ?", domainId);
    }

    /**
     * The same reconciler with orphan pruning on. Built rather than patched:
     * the switch is a constructor argument, and a test that reaches into the
     * bean to flip it would pass even if the field stopped being read.
     */
    private List<DomainRecordsReconciler.Reconciliation> reconcileWithPruning() {
        DnsProperties pruning = new DnsProperties(dnsProperties.provider(), dnsProperties.google(),
                dnsProperties.recordTtl(), true, dnsProperties.connectTimeout(),
                dnsProperties.readTimeout());
        return new DomainRecordsReconciler(zone, pruning, settingsService, domainRepository,
                recordRepository, applyJob, nameLocks, transactionTemplate).reconcile();
    }

    private static DesiredSet set(String name, DnsRecordType type, String value) {
        return new DesiredSet(name, type, List.of(value), 300);
    }

    private Domain external(String label) {
        String fqdn = label + "-" + UUID.randomUUID().toString().substring(0, 8) + ".pusan.dev";
        long id = jdbcTemplate.queryForObject("""
                insert into domains (workspace_id, org_id, kind, fqdn, root_domain, status,
                                     renew_due_at)
                values (?, ?, 'EXTERNAL'::domain_kind, ?, 'pusan.dev', 'ACTIVE'::domain_status,
                        now() + interval '180 days')
                returning id
                """, Long.class, workspaceId, orgId, fqdn);
        return domainRepository.findById(id).orElseThrow();
    }
}
