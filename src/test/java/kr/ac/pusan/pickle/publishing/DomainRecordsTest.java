package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.publishing.DomainRecordPolicy.DesiredSet;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordType;
import kr.ac.pusan.pickle.publishing.dns.RecordingDnsRecordProvider;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * A domain's record sets, from the edit through to what reaches the zone.
 *
 * <p>The provider here is an in-memory zone rather than a stub that answers
 * yes: the assertions read what the zone holds afterwards, so a push that
 * never happened cannot pass. That distinction is the one this round has been
 * bitten by twice — a mock that reflects the client back proves nothing about
 * the client.</p>
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, DomainRecordsTest.ZoneConfig.class})
class DomainRecordsTest {

    @TestConfiguration
    static class ZoneConfig {
        @Bean
        @Primary
        RecordingDnsRecordProvider recordingProvider() {
            return new RecordingDnsRecordProvider();
        }
    }

    // Own proxmox_vmid range; see the note in the other publishing suites.
    private static final AtomicInteger VMID_SEQ = new AtomicInteger(909_000);
    private static final String PUBLIC_V4 = "93.184.216.34";

    @Autowired
    private DomainRecordsService records;
    @Autowired
    private DomainRecordApplyJob applyJob;
    @Autowired
    private DomainRepository domainRepository;
    @Autowired
    private RecordingDnsRecordProvider zone;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private long workspaceId;

    @BeforeEach
    void setUp() {
        zone.reset();
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, "drt-" + UUID.randomUUID().toString().substring(0, 8));
    }

    @Test
    void anEditReachesTheZoneAsTheSetsItAsksFor() {
        Domain domain = external("reaches");
        records.replace(domain, List.of(
                set("", DnsRecordType.A, PUBLIC_V4),
                set("www", DnsRecordType.CNAME, "pages.github.io."),
                set("_acme-challenge", DnsRecordType.TXT, "token-1")));

        applyJob.apply(domain.getId());

        assertThat(zone.recordSet(domain.getFqdn(), DnsRecordType.A).values())
                .containsExactly(PUBLIC_V4);
        assertThat(zone.recordSet("www." + domain.getFqdn(), DnsRecordType.CNAME).values())
                .containsExactly("pages.github.io.");
        assertThat(zone.recordSet("_acme-challenge." + domain.getFqdn(), DnsRecordType.TXT)
                .values()).containsExactly("token-1");
        assertThat(records.list(domain.getId()))
                .allMatch(r -> r.getStatus() == DomainRecordStatus.APPLIED);
    }

    @Test
    void resendingTheSameDesiredStateSpendsNoZoneWrite() {
        Domain domain = external("idempotent");
        records.replace(domain, List.of(set("", DnsRecordType.A, PUBLIC_V4)));
        applyJob.apply(domain.getId());
        int after = zone.calls().size();

        records.replace(domain, List.of(set("", DnsRecordType.A, PUBLIC_V4)));
        applyJob.apply(domain.getId());

        // The edit is a desired state, so sending it twice is one change. A
        // diff that missed this would rewrite the zone on every save.
        assertThat(zone.calls()).hasSize(after);
    }

    @Test
    void aSetDroppedFromTheDesiredStateLeavesTheZone() {
        Domain domain = external("drops");
        records.replace(domain, List.of(
                set("", DnsRecordType.A, PUBLIC_V4),
                set("www", DnsRecordType.CNAME, "pages.github.io.")));
        applyJob.apply(domain.getId());
        assertThat(zone.has("www." + domain.getFqdn(), "CNAME")).isTrue();

        records.replace(domain, List.of(set("", DnsRecordType.A, PUBLIC_V4)));
        applyJob.apply(domain.getId());

        assertThat(zone.has("www." + domain.getFqdn(), "CNAME")).isFalse();
        assertThat(records.list(domain.getId())).hasSize(1);
    }

    @Test
    void aSetThatNeverReachedTheZoneIsDroppedRatherThanQueuedForRemoval() {
        Domain domain = external("never-applied");
        records.replace(domain, List.of(set("www", DnsRecordType.A, PUBLIC_V4)));
        // No apply: the set is still owed its first write.
        records.replace(domain, List.of());

        // Queued for removal it would retry forever against a set the zone
        // never held, and the reconciler would keep finding work to do.
        assertThat(records.list(domain.getId())).isEmpty();
        applyJob.apply(domain.getId());
        assertThat(zone.calls()).isEmpty();
    }

    @Test
    void removingEveryRecordTakesThemOutOfTheZone() {
        Domain domain = external("reclaim");
        records.replace(domain, List.of(
                set("", DnsRecordType.A, PUBLIC_V4),
                set("www", DnsRecordType.TXT, "hello")));
        applyJob.apply(domain.getId());

        records.removeAll(domain.getId());
        applyJob.apply(domain.getId());

        // Every type, not just A: a name reclaimed with its AAAA, CNAME or TXT
        // still standing hands the next owner the last one's DNS.
        assertThat(zone.has(domain.getFqdn(), "A")).isFalse();
        assertThat(zone.has("www." + domain.getFqdn(), "TXT")).isFalse();
    }

    @Test
    void aNewerEditSupersedesAPushInFlight() {
        Domain domain = external("supersede");
        records.replace(domain, List.of(set("", DnsRecordType.A, PUBLIC_V4)));
        long stale = domainRepository.findById(domain.getId()).orElseThrow()
                .getRecordsGeneration();
        // A second edit lands before the first push runs, so the first is no
        // longer the current intent and must not write.
        records.replace(domain, List.of(set("", DnsRecordType.A, "93.184.216.35")));

        applyJob.apply(domain.getId());

        assertThat(domainRepository.findById(domain.getId()).orElseThrow()
                .getRecordsGeneration()).isGreaterThan(stale);
        assertThat(zone.recordSet(domain.getFqdn(), DnsRecordType.A).values())
                .containsExactly("93.184.216.35");
    }

    @Test
    void aProviderFailureIsRecordedAndTheSetStaysOwed() {
        Domain domain = external("fails");
        records.replace(domain, List.of(set("", DnsRecordType.A, PUBLIC_V4)));
        zone.failWith("zone unreachable");

        applyJob.apply(domain.getId());

        assertThat(records.list(domain.getId()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getStatus()).isEqualTo(DomainRecordStatus.FAILED);
                    assertThat(r.getLastError()).contains("zone unreachable");
                });

        zone.failWith(null);
        applyJob.apply(domain.getId());
        assertThat(zone.recordSet(domain.getFqdn(), DnsRecordType.A).values())
                .containsExactly(PUBLIC_V4);
    }

    @Test
    void aRefusedValueNeverReachesTheZone() {
        Domain domain = external("refused");
        assertThatThrownBy(() -> records.replace(domain,
                List.of(set("", DnsRecordType.A, "164.125.249.87"))))
                .isInstanceOf(ApiException.class);
        assertThat(records.list(domain.getId())).isEmpty();
        assertThat(zone.calls()).isEmpty();
    }

    @Test
    void aDomainThisPlatformServesHasNoEditableRecords() {
        Domain platform = platformDomain();

        // The zone reconciler owns that name's A record. An edit here would be
        // a second writer on it, and the two would undo each other forever.
        assertThatThrownBy(() -> records.replace(platform,
                List.of(set("", DnsRecordType.A, PUBLIC_V4))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> records.removeAll(platform.getId()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(zone.calls()).isEmpty();
    }

    private static DesiredSet set(String name, DnsRecordType type, String value) {
        return new DesiredSet(name, type, List.of(value), 300);
    }

    /** A name of a kind this platform serves itself, so vm_id is not null. */
    private Domain platformDomain() {
        long requesterId = SeedFixtures.orgadminId(jdbcTemplate);
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId,
                requesterId, "레코드 편집 거절 확인", null, 1, 1024, 10);
        RequestFixtures.approveVmRequest(jdbcTemplate, requestId, requesterId, null, 1, 1024, 10);
        String host = "drt-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values ((select min(id) from nodes), ?, ?, ?, ?, ?,
                        (select min(id) from os_images), 1, 1024, 10, ?, 'RUNNING'::vm_status)
                returning id
                """, Long.class, workspaceId, orgId, requestId, host, host,
                VMID_SEQ.incrementAndGet());
        String fqdn = "served-" + UUID.randomUUID().toString().substring(0, 8) + ".pusan.dev";
        long id = jdbcTemplate.queryForObject("""
                insert into domains (workspace_id, org_id, vm_id, kind, fqdn, root_domain, status)
                values (?, ?, ?, 'PLATFORM'::domain_kind, ?, 'pusan.dev', 'ACTIVE'::domain_status)
                returning id
                """, Long.class, workspaceId, orgId, vmId, fqdn);
        return domainRepository.findById(id).orElseThrow();
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
