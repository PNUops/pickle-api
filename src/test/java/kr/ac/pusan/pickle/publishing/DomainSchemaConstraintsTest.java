package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The rules the domain schema enforces on its own, exercised by trying to
 * break each one.
 *
 * <p>Every check here is a rule the application also keeps, which is exactly
 * why it is worth a test at this level: application code is where a rule is
 * kept until someone writes a path that forgets it, and these are the ones a
 * forgotten path would put a row in the database wrong. A green run says the
 * constraint exists and bites, not merely that it parsed.</p>
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class DomainSchemaConstraintsTest {

    // Every suite in the shared embedded PG needs its OWN proxmox_vmid base
    // (vms_proxmox_vmid_active_uq is global): pick an unused range by grepping
    // VMID_SEQ across src/test before adding one.
    private static final AtomicInteger VMID_SEQ = new AtomicInteger(908_000);

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;

    private long orgId;
    private long workspaceId;
    private long ownerId;
    private long imageId;
    private long nodeId;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        String slug = "dsc-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
        User owner = new User("owner." + slug + "@pusan.ac.kr", "{test-no-login}", "신예준");
        owner.setRole(UserRole.USER);
        owner.setStatus(UserStatus.ACTIVE);
        ownerId = userRepository.save(owner).getId();
    }

    @Test
    void aNameThatServesNoVmIsAcceptedAtAll() {
        // The one positive case. It reads as trivial and is not: before this
        // round the column was NOT NULL, so this insert was the thing the
        // schema refused, and every other test here only proves what is still
        // refused.
        long id = insertExternal(fqdn("ok"), "now() + interval '180 days'");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from domains where id = ? and vm_id is null", Long.class, id))
                .isEqualTo(1);
    }

    @Test
    void theVmColumnAndTheKindAgreeInBothDirections() {
        // An equivalence, not a permission. Tested both ways round because a
        // one-sided rule would pass the half of this that a one-sided test
        // happens to exercise, and the two halves guard different mistakes: a
        // name with no VM claiming one, and a name that serves a VM losing it.
        long vmId = createVm();
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into domains (vm_id, workspace_id, org_id, kind, fqdn, root_domain,
                                     status, renew_due_at)
                values (?, ?, ?, 'EXTERNAL'::domain_kind, ?, 'pusan.dev',
                        'ACTIVE'::domain_status, now())
                """, vmId, workspaceId, orgId, fqdn("with-vm")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domains_vm_id_kind_check");

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into domains (workspace_id, org_id, kind, fqdn, root_domain, status)
                values (?, ?, 'PLATFORM'::domain_kind, ?, 'pusan.dev', 'ACTIVE'::domain_status)
                """, workspaceId, orgId, fqdn("no-vm")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domains_vm_id_kind_check");
    }

    private long createVm() {
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, ownerId,
                "스키마 제약 시험", imageId, 1, 1024, 10);
        String hostname = "dsc-vm-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, 'RUNNING'::vm_status)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                imageId, VMID_SEQ.incrementAndGet());
    }

    @Test
    void externalWithoutARenewalDeadlineIsRefused() {
        // Without this the deadline is optional, and a row created by a path
        // that forgot it is a name nothing ever reclaims.
        assertThatThrownBy(() -> insertExternal(fqdn("no-deadline"), "null"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domains_renew_due_at_kind_check");
    }

    @Test
    void aKindTheProxyDoesNotServeMayNotHoldAnAppliedRecordState() {
        // dns_status describes the platform's own A record pointing at the
        // proxy. A kind the proxy does not serve has no such record, and the
        // constraint is written as a list of the kinds that do rather than as
        // an exclusion of CUSTOM, so a kind added later is outside it.
        long id = insertExternal(fqdn("dns"), "now() + interval '180 days'");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update domains set dns_status = 'APPLIED', dns_applied_at = now() where id = ?",
                id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domains_dns_status_proxy_check");
    }

    @Test
    void replacingTheOldRecordStateRuleKeepsWhatItKept() {
        // This round swapped a live constraint for one asked the other way
        // round, so what the old one held has to be shown still held: a custom
        // domain pinned to NONE, and the kinds the proxy serves free to move.
        // A replacement is only safe if the rules it replaces still bite.
        long vmId = createVm();
        long custom = jdbcTemplate.queryForObject("""
                insert into domains (vm_id, workspace_id, org_id, kind, fqdn,
                                     verification_token, status)
                values (?, ?, ?, 'CUSTOM'::domain_kind, ?, 'pv-x', 'PENDING'::domain_status)
                returning id
                """, Long.class, vmId, workspaceId, orgId, "c-" + fqdn("x") + ".example.com");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "update domains set dns_status = 'APPLIED', dns_applied_at = now() where id = ?",
                custom))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domains_dns_status_proxy_check");

        long platform = jdbcTemplate.queryForObject("""
                insert into domains (vm_id, workspace_id, org_id, kind, fqdn, root_domain, status)
                values (?, ?, ?, 'PLATFORM'::domain_kind, ?, 'pusan.dev', 'ACTIVE'::domain_status)
                returning id
                """, Long.class, vmId, workspaceId, orgId, fqdn("served"));
        jdbcTemplate.update(
                "update domains set dns_status = 'APPLIED', dns_applied_at = now() where id = ?",
                platform);
        assertThat(jdbcTemplate.queryForObject(
                "select dns_status::text from domains where id = ?", String.class, platform))
                .isEqualTo("APPLIED");
    }

    @Test
    void oneLiveRecordSetPerNameAndType() {
        long domainId = insertExternal(fqdn("records"), "now() + interval '180 days'");
        insertRecord(domainId, "", "A", "{203.0.113.10}", 300);

        assertThatThrownBy(() -> insertRecord(domainId, "", "A", "{203.0.113.11}", 300))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domain_records_live_idx");

        // The same name and type is free again once the set is taken down, the
        // same shape the FQDN index uses for a released name.
        jdbcTemplate.update("update domain_records set status = 'REMOVED' where domain_id = ?",
                domainId);
        insertRecord(domainId, "", "A", "{203.0.113.11}", 300);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from domain_records where domain_id = ?", Long.class, domainId))
                .isEqualTo(2);
    }

    @Test
    void recordSetsAreBounded() {
        long domainId = insertExternal(fqdn("bounds"), "now() + interval '180 days'");

        assertThatThrownBy(() -> insertRecord(domainId, "", "TXT", "{}", 300))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domain_records_values_check");
        assertThatThrownBy(() -> insertRecord(domainId, "", "A", "{203.0.113.10}", 30))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domain_records_ttl_check");
        assertThatThrownBy(() -> insertRecord(domainId, "", "A", "{203.0.113.10}", 90000))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domain_records_ttl_check");

        // The upper bound is the one the zone quota argument rests on, so it is
        // the one worth showing bites.
        String eleven = "{" + String.join(",", java.util.Collections.nCopies(11, "v")) + "}";
        assertThatThrownBy(() -> insertRecord(domainId, "", "TXT", eleven, 300))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domain_records_values_check");
        assertThatThrownBy(() -> insertRecord(domainId, "", "TXT", "{NULL}", 300))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domain_records_values_check");
    }

    @Test
    void recordNamesAreFoldedBeforeTheyReachTheIndex() {
        // The unique index is case sensitive and the provider is not, so an
        // unfolded name would put two live rows behind one record set in the
        // zone. Refused at the column rather than trusted to the caller.
        long domainId = insertExternal(fqdn("names"), "now() + interval '180 days'");
        insertRecord(domainId, "www", "A", "{203.0.113.10}", 300);

        assertThatThrownBy(() -> insertRecord(domainId, "WWW", "A", "{203.0.113.11}", 300))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domain_records_name_check");
        // An absolute name would be ambiguous about whose zone it names.
        assertThatThrownBy(() -> insertRecord(domainId, "www.pusan.dev.", "A",
                "{203.0.113.11}", 300))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("domain_records_name_check");
        // Underscore labels stay legal: ACME and service verification use them.
        insertRecord(domainId, "_acme-challenge", "TXT", "{token}", 300);
    }

    private long insertExternal(String fqdn, String renewDueAtSql) {
        return jdbcTemplate.queryForObject("""
                insert into domains (workspace_id, org_id, kind, fqdn, root_domain, status,
                                     renew_due_at)
                values (?, ?, 'EXTERNAL'::domain_kind, ?, 'pusan.dev', 'ACTIVE'::domain_status,
                        """ + renewDueAtSql + """
                       )
                returning id
                """, Long.class, workspaceId, orgId, fqdn);
    }

    private void insertRecord(long domainId, String name, String type, String rrdatas, int ttl) {
        jdbcTemplate.update("""
                insert into domain_records (domain_id, name, type, rrdatas, ttl)
                values (?, ?, ?::domain_record_type, ?::text[], ?)
                """, domainId, name, type, rrdatas, ttl);
    }

    private static String fqdn(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + ".pusan.dev";
    }
}
