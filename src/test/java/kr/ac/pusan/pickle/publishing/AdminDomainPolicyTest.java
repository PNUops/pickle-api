package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.publishing.dto.UpdateDomainRenewalRequest;
import kr.ac.pusan.pickle.publishing.dto.UpdateDomainRootRequest;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** What an administrator may decide about a root and about one name under it. */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminDomainPolicyTest {

    @Autowired
    private AdminDomainRootService rootService;
    @Autowired
    private AdminPublishingService adminPublishingService;
    @Autowired
    private DnsDomainService domainService;
    @Autowired
    private DomainRepository domainRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private long workspaceId;
    private AuthenticatedUser admin;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        jdbcTemplate.update("update orgs set status = 'ACTIVE' where id = ?", orgId);
        String slug = "adp-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
        long userId = jdbcTemplate.queryForObject("""
                insert into users (email, name, role, status, password_hash)
                values (?, ?, 'SYS_ADMIN', 'ACTIVE', 'x') returning id
                """, Long.class, slug + "@pusan.ac.kr", "관리자");
        admin = new AuthenticatedUser(userId,
                jdbcTemplate.queryForObject("select public_id from users where id = ?", UUID.class,
                        userId),
                slug + "@pusan.ac.kr", UserRole.SYS_ADMIN, Map.of());
        jdbcTemplate.update("delete from domain_roots");
        // Inserted without the policy column on purpose: the default is what
        // keeps every name already in the ground issued the way it was issued,
        // and naming the value here would hide a change to it.
        jdbcTemplate.update("insert into domain_roots (root_domain, org_id) values (?, ?)",
                "pusan.dev", orgId);
    }

    @Test
    void aRootStartsOpenSoNothingChangesForNamesAlreadyIssuedThisWay() {
        assertThat(rootService.list(admin))
                .singleElement()
                .satisfies(root -> {
                    assertThat(root.rootDomain()).isEqualTo("pusan.dev");
                    assertThat(root.autoApprove()).isTrue();
                });
    }

    @Test
    void turningReviewOnChangesWhatArrivesNextAndNothingAlreadyStanding() {
        Domain existing = domainService.issue(workspaceId, "adpstanding", "pusan.dev");

        rootService.update(admin, "pusan.dev", new UpdateDomainRootRequest(false), "127.0.0.1");

        // The name issued under the open policy is untouched. Reaching back to
        // un-approve it would take away something a rule granted at the time it
        // granted it.
        assertThat(domainRepository.findById(existing.getId())).get()
                .extracting(Domain::getStatus).isEqualTo(DomainStatus.ACTIVE);
        assertThat(rootService.list(admin)).singleElement()
                .satisfies(root -> assertThat(root.autoApprove()).isFalse());
    }

    @Test
    void theRootListingSaysHowManyNamesStandUnderEachRoot() {
        // A delta, not an absolute. The count is per root and the suite shares
        // one database, so every name any test in this class issued is under
        // this root too — asserting a literal would be asserting the order the
        // methods happened to run in.
        long before = rootService.list(admin).getFirst().issuedNames();

        domainService.issue(workspaceId, "adpcounted", "pusan.dev");

        // The count is what makes the policy legible: turning review on for a
        // root with names under it is a different act from doing it to an empty
        // one.
        assertThat(rootService.list(admin)).singleElement()
                .satisfies(root -> assertThat(root.issuedNames()).isEqualTo(before + 1));
    }

    @Test
    void anUnknownRootIsNotSilentlyCreatedByEditingItsPolicy() {
        // Rows arrive with the deployment that created the zone. A policy edit
        // that conjured one would issue names into a zone nobody delegated.
        assertThatThrownBy(() -> rootService.update(admin, "nowhere.example",
                new UpdateDomainRootRequest(false), "127.0.0.1"))
                .isInstanceOf(ApiException.class);
        assertThat(rootService.list(admin)).hasSize(1);
    }

    @Test
    void theDeadlineMovesInBothDirections() {
        Domain domain = domainService.issue(workspaceId, "adpdeadline", "pusan.dev");
        Instant pulledIn = Instant.now().plus(3, ChronoUnit.DAYS);

        var view = adminPublishingService.updateRenewal(admin, domain.getPublicId(),
                new UpdateDomainRenewalRequest(pulledIn, "학기 종료"), "127.0.0.1");

        // Pulling it in is how a name is wound down without taking it away
        // today, which is gentler than a forced release.
        assertThat(view.renewDueAt()).isCloseTo(pulledIn,
                org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));

        // And out again. Only ever testing one direction would let a mutation
        // that clamps the deadline to "no later than it already was" pass, and
        // the ordinary case — a name that should last the term — is the push.
        Instant pushedOut = Instant.now().plus(400, ChronoUnit.DAYS);
        var extended = adminPublishingService.updateRenewal(admin, domain.getPublicId(),
                new UpdateDomainRenewalRequest(pushedOut, null), "127.0.0.1");
        assertThat(extended.renewDueAt()).isCloseTo(pushedOut,
                org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
    }

    @Test
    void aDeadlineInThePastIsRefusedRatherThanQuietlyReleasingTheName() {
        Domain domain = domainService.issue(workspaceId, "adppast", "pusan.dev");

        // The sweeper would lapse it that night — records down, name into the
        // grace — and the owner would never hear, because the renewal notices
        // only fire ahead of a deadline still ahead. That is a forced release
        // with a day's delay and no announcement, and forced release is the
        // path that exists for taking a name away.
        assertThatThrownBy(() -> adminPublishingService.updateRenewal(admin, domain.getPublicId(),
                new UpdateDomainRenewalRequest(Instant.now().minus(1, ChronoUnit.DAYS), null),
                "127.0.0.1"))
                .isInstanceOf(ApiException.class);
        assertThat(domainRepository.findById(domain.getId())).get()
                .extracting(Domain::getRenewDueAt)
                .satisfies(due -> assertThat((Instant) due).isAfter(Instant.now()));
    }

    @Test
    void aRootBelongingToAnotherInstitutionIsNotThisAdministratorsToChange() {
        long otherOrgId = jdbcTemplate.queryForObject(
                "insert into orgs (name, status) values (?, 'ACTIVE') returning id",
                Long.class, "다른 기관 " + UUID.randomUUID().toString().substring(0, 8));
        jdbcTemplate.update(
                "insert into domain_roots (root_domain, org_id) values (?, ?)",
                "other.example", otherOrgId);
        AuthenticatedUser orgAdmin = orgTierAdmin();

        // Reading every root is deliberate — the name space is shared and a
        // collision has to be visible. Writing one is not: the policy of an
        // institution's own name space is theirs.
        assertThatThrownBy(() -> rootService.update(orgAdmin, "other.example",
                new UpdateDomainRootRequest(false), "127.0.0.1"))
                .isInstanceOf(ApiException.class);
        assertThat(jdbcTemplate.queryForObject(
                "select auto_approve from domain_roots where root_domain = 'other.example'",
                Boolean.class)).isTrue();
    }

    @Test
    void aNameWithNoDeadlineOfItsOwnRefusesTheAdjustment() {
        // A platform subdomain's life is its VM's and a custom domain's is its
        // owner's DNS. Inventing a deadline here would be this screen making up
        // a lifetime nothing else honours.
        // A vm_id is not optional for this kind: the check constraint makes
        // "external" and "has no VM" the same statement, so a platform
        // subdomain without one cannot exist to be asked about.
        long vmBackedId = jdbcTemplate.queryForObject("""
                insert into domains (workspace_id, org_id, vm_id, kind, fqdn, root_domain, status)
                values (?, ?, ?, 'PLATFORM'::domain_kind, ?, 'pusan.dev', 'ACTIVE'::domain_status)
                returning id
                """, Long.class, workspaceId, orgId, servedVm(),
                "adpplatform-" + UUID.randomUUID().toString().substring(0, 8) + ".pusan.dev");
        UUID publicId = jdbcTemplate.queryForObject(
                "select public_id from domains where id = ?", UUID.class, vmBackedId);

        assertThatThrownBy(() -> adminPublishingService.updateRenewal(admin, publicId,
                new UpdateDomainRenewalRequest(Instant.now().plus(30, ChronoUnit.DAYS), null),
                "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("사용 기한이 없는");
    }

    @Test
    void aReadOnlyAdministratorSeesTheRecordsOfItsOwnInstitution() {
        Domain domain = domainService.issue(workspaceId, "adpviewer", "pusan.dev");
        AuthenticatedUser viewer = orgTierViewer();

        // Every other caller of this scope check is a write, and a write asks
        // whether the account may act in the organisation. Reading asks a wider
        // question, and asking the write question here hid an institution's own
        // names from its own read-only administrator: the row stood in the
        // listing and the drawer answered 404.
        assertThat(adminPublishingService.listRecords(viewer, domain.getPublicId())).isEmpty();

        // The wider read does not become a wider write. Still someone else's
        // organisation, and still nothing this account may change in its own.
        assertThatThrownBy(() -> adminPublishingService.updateRenewal(viewer, domain.getPublicId(),
                new UpdateDomainRenewalRequest(Instant.now().plus(30, ChronoUnit.DAYS), null),
                "127.0.0.1"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void anotherInstitutionsRecordsStayOutOfReach() {
        Domain domain = domainService.issue(workspaceId, "adpoutside", "pusan.dev");
        AuthenticatedUser outsider = orgTierViewerOf(jdbcTemplate.queryForObject(
                "insert into orgs (name, status) values (?, 'ACTIVE') returning id",
                Long.class, "타 기관 " + UUID.randomUUID().toString().substring(0, 8)));

        assertThatThrownBy(() -> adminPublishingService.listRecords(outsider, domain.getPublicId()))
                .isInstanceOf(ApiException.class);
    }

    /** A read-only organisation-tier account in the seeded organisation. */
    private AuthenticatedUser orgTierViewer() {
        return orgTierViewerOf(orgId);
    }

    private AuthenticatedUser orgTierViewerOf(long inOrgId) {
        String slug = "adpview-" + UUID.randomUUID().toString().substring(0, 8);
        long userId = jdbcTemplate.queryForObject("""
                insert into users (email, name, role, status, password_hash)
                values (?, ?, 'ORG_VIEWER', 'ACTIVE', 'x') returning id
                """, Long.class, slug + "@pusan.ac.kr", "기관 열람자");
        jdbcTemplate.update(
                "insert into user_org_roles (user_id, org_id, role) values (?, ?, 'ORG_VIEWER')",
                userId, inOrgId);
        return new AuthenticatedUser(userId,
                jdbcTemplate.queryForObject("select public_id from users where id = ?", UUID.class,
                        userId),
                slug + "@pusan.ac.kr", UserRole.ORG_VIEWER, Map.of(inOrgId, UserRole.ORG_VIEWER));
    }

    /** An organisation-tier administrator of the seeded organisation only. */
    private AuthenticatedUser orgTierAdmin() {
        String slug = "adporg-" + UUID.randomUUID().toString().substring(0, 8);
        long userId = jdbcTemplate.queryForObject("""
                insert into users (email, name, role, status, password_hash)
                values (?, ?, 'ORG_ADMIN', 'ACTIVE', 'x') returning id
                """, Long.class, slug + "@pusan.ac.kr", "기관 관리자");
        jdbcTemplate.update(
                "insert into user_org_roles (user_id, org_id, role) values (?, ?, 'ORG_ADMIN')",
                userId, orgId);
        return new AuthenticatedUser(userId,
                jdbcTemplate.queryForObject("select public_id from users where id = ?", UUID.class,
                        userId),
                slug + "@pusan.ac.kr", UserRole.ORG_ADMIN, Map.of(orgId, UserRole.ORG_ADMIN));
    }

    /** A VM a served domain can hang from, built the way the surface test does. */
    private long servedVm() {
        long requestId = kr.ac.pusan.pickle.support.RequestFixtures.insertVmRequest(jdbcTemplate,
                workspaceId, orgId, admin.id(), "기한 조정 거절 확인", null, 1, 1024, 10);
        kr.ac.pusan.pickle.support.RequestFixtures.approveVmRequest(jdbcTemplate, requestId,
                admin.id(), null, 1, 1024, 10);
        String host = "adp-vm-" + UUID.randomUUID().toString().substring(0, 10);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values ((select min(id) from nodes), ?, ?, ?, ?, ?,
                        (select min(id) from os_images), 1, 1024, 10, ?, 'RUNNING'::vm_status)
                returning id
                """, Long.class, workspaceId, orgId, requestId, host, host,
                912_000 + (int) (System.nanoTime() % 900));
    }
}
