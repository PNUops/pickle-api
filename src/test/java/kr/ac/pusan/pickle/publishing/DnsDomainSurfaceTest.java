package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.publishing.dto.CreateDnsDomainRequest;
import kr.ac.pusan.pickle.publishing.dto.DnsDomainView;
import kr.ac.pusan.pickle.publishing.dto.ReplaceDnsRecordSetsRequest;
import kr.ac.pusan.pickle.publishing.dto.ReplaceDnsRecordSetsRequest.DesiredRecordSet;
import kr.ac.pusan.pickle.publishing.dns.DnsRecordType;
import kr.ac.pusan.pickle.resource.ResourceTypeAdapter;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Issuing a name on its own: what it takes, who it belongs to afterwards, and
 * what the resource machinery may be told about it.
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class DnsDomainSurfaceTest {

    @Autowired
    private DnsDomainService service;
    @Autowired
    private DnsDomainQueryService queryService;
    @Autowired
    private DomainResourceAdapter adapter;
    @Autowired
    private List<ResourceTypeAdapter> adapters;
    @Autowired
    private DomainRepository domainRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long orgId;
    private long workspaceId;
    private UUID workspacePublicId;
    private AuthenticatedUser owner;

    @BeforeEach
    void setUp() {
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        // Reset rather than assumed: the suite shares one seeded organisation
        // and the disabled-root test leaves it DISABLED, which every other test
        // here would then be running against.
        jdbcTemplate.update("update orgs set status = 'ACTIVE' where id = ?", orgId);
        String slug = "dds-" + UUID.randomUUID().toString().substring(0, 8);
        workspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, slug);
        workspacePublicId = jdbcTemplate.queryForObject(
                "select public_id from workspaces where id = ?", UUID.class, workspaceId);
        long userId = createUser("owner." + slug + "@pusan.ac.kr");
        jdbcTemplate.update(
                "insert into workspace_members (workspace_id, user_id, role) values (?, ?, 'OWNER')",
                workspaceId, userId);
        owner = new AuthenticatedUser(userId,
                jdbcTemplate.queryForObject("select public_id from users where id = ?", UUID.class,
                        userId),
                "owner." + slug + "@pusan.ac.kr", UserRole.USER, Map.of());
        jdbcTemplate.update("delete from domain_roots");
        jdbcTemplate.update("insert into domain_roots (root_domain, org_id) values (?, ?)",
                "pusan.dev", orgId);
    }

    @Test
    void issuingTakesNoApprovalAndLeavesTheIssuerItsOnlyOwner() {
        DnsDomainView created = service.create(owner, request("mysite"), "127.0.0.1");

        assertThat(created.fqdn()).isEqualTo("mysite.pusan.dev");
        assertThat(created.status()).isEqualTo(DomainStatus.ACTIVE);
        // Nothing waits on a human, and nothing is open by default.
        assertThat(created.renewDueAt()).isAfter(Instant.now());
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from resource_access_grants
                 where resource_type = 'DOMAIN'::resource_type and role = 'OWNER'
                   and user_id = ?
                """, Long.class, owner.id())).isEqualTo(1);
    }

    @Test
    void theOrganisationComesFromTheRootRatherThanFromTheRequester() {
        // The person issuing a name for their own site is never asked which
        // organisation it belongs to; the root they picked already says.
        DnsDomainView created = service.create(owner, request("fromroot"), "127.0.0.1");

        assertThat(jdbcTemplate.queryForObject("""
                select org_id from domains where fqdn = ?
                """, Long.class, created.fqdn())).isEqualTo(orgId);
    }

    @Test
    void aRootWithNoRowCannotIssue() {
        jdbcTemplate.update("delete from domain_roots");

        // The setting still allows the root. Without a row there is no
        // organisation to give the name, and a name with none is invisible to
        // every organisation administrator.
        assertThatThrownBy(() -> service.create(owner, request("orphan"), "127.0.0.1"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void aNameHeldByAnotherRowIsRefused() {
        service.create(owner, request("taken"), "127.0.0.1");

        assertThatThrownBy(() -> service.create(owner, request("taken"), "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("이미 사용 중");
    }

    @Test
    void theWorkspaceCapCountsNamesItIsStillHolding() {
        for (int i = 0; i < DnsDomainService.DEFAULT_DOMAINS_PER_WORKSPACE; i++) {
            service.create(owner, request("capped" + i), "127.0.0.1");
        }

        assertThatThrownBy(() -> service.create(owner, request("onemore"), "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("더 만들 수 없습니다");
    }

    @Test
    void theAdapterRefusesADomainThisPlatformServes() {
        // The sharpest trap in reusing the domains table: three of its four
        // kinds belong to a VM that already has an access list, and a second
        // list hung on one of those rows would disagree with it about who may
        // reach the same name.
        String fqdn = "served-" + UUID.randomUUID().toString().substring(0, 8) + ".pusan.dev";
        long vmId = servedVm();
        UUID publicId = jdbcTemplate.queryForObject("""
                insert into domains (workspace_id, org_id, vm_id, kind, fqdn, root_domain, status)
                values (?, ?, ?, 'PLATFORM'::domain_kind, ?, 'pusan.dev', 'ACTIVE'::domain_status)
                returning public_id
                """, UUID.class, workspaceId, orgId, vmId, fqdn);

        assertThat(adapter.identifyByPublicId(publicId)).isEmpty();
        assertThat(queryService.listPage(owner, workspacePublicId,
                org.springframework.data.domain.PageRequest.of(0, 20)).getContent())
                .noneMatch(view -> view.fqdn().equals(fqdn));
    }

    @Test
    void theWorkspaceDeleteGateSeesThisKindWithoutBeingTold() {
        service.create(owner, request("blocks"), "127.0.0.1");

        // The gate walks every adapter rather than naming kinds, so this one
        // blocks deletion by existing.
        assertThat(adapters.stream()
                .filter(a -> a.type() == ResourceType.DOMAIN)
                .anyMatch(a -> a.countLiveInWorkspace(workspaceId) > 0)).isTrue();
    }

    @Test
    void aReleasedNameStillCountsAgainstTheWorkspace() {
        DnsDomainView created = service.create(owner, request("released"), "127.0.0.1");
        service.delete(owner, created.id(), "127.0.0.1");

        // Released is not gone: the name is out of the shared space for its
        // whole grace, so letting it stop counting would let one workspace hold
        // any number of names by releasing and re-issuing.
        assertThat(adapter.countLiveInWorkspace(workspaceId)).isEqualTo(1);
    }

    @Test
    void recordsAreEditedThroughTheNameAndRefusedWhenTheValueIsOurs() {
        DnsDomainView created = service.create(owner, request("records"), "127.0.0.1");

        service.replaceRecords(owner, created.id(), new ReplaceDnsRecordSetsRequest(List.of(
                new DesiredRecordSet("", DnsRecordType.A, List.of("93.184.216.34"), 300))),
                "127.0.0.1");
        assertThat(service.listRecords(owner, created.id())).singleElement()
                .satisfies(set -> assertThat(set.values()).containsExactly("93.184.216.34"));

        assertThatThrownBy(() -> service.replaceRecords(owner, created.id(),
                new ReplaceDnsRecordSetsRequest(List.of(
                        new DesiredRecordSet("", DnsRecordType.A, List.of("164.125.249.87"), 300))),
                "127.0.0.1"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void renewingMovesTheDeadlineAFullPeriodFromNow() {
        DnsDomainView created = service.create(owner, request("renews"), "127.0.0.1");
        jdbcTemplate.update("update domains set renew_due_at = now() + interval '2 days'"
                + " where fqdn = ?", created.fqdn());

        DnsDomainView renewed = service.renew(owner, created.id(), "127.0.0.1");

        assertThat(renewed.renewDueAt()).isAfter(Instant.now().plusSeconds(60L * 60 * 24 * 100));
    }

    @Test
    void aWorkspaceTakesItsOwnReservedNameBack() {
        DnsDomainView created = service.create(owner, request("comeback"), "127.0.0.1");
        service.replaceRecords(owner, created.id(), new ReplaceDnsRecordSetsRequest(List.of(
                new DesiredRecordSet("", DnsRecordType.A, List.of("93.184.216.34"), 300))),
                "127.0.0.1");
        service.delete(owner, created.id(), "127.0.0.1");

        DnsDomainView revived = service.create(owner, request("comeback"), "127.0.0.1");

        // The whole point of the grace: without this it keeps the name from
        // everybody including the person it is being kept for.
        assertThat(revived.fqdn()).isEqualTo(created.fqdn());
        assertThat(revived.releasedAt()).isNull();
        assertThat(revived.renewDueAt()).isAfter(Instant.now());
        // The name comes back; the records do not. They were taken down when it
        // was released, and handing them back would republish a site its owner
        // had stopped.
        assertThat(service.listRecords(owner, revived.id())).isEmpty();
    }

    @Test
    void aReservedNameIsNotRevivableByAnotherWorkspace() {
        DnsDomainView created = service.create(owner, request("notyours"), "127.0.0.1");
        service.delete(owner, created.id(), "127.0.0.1");
        long otherWorkspaceId = jdbcTemplate.queryForObject(
                "insert into workspaces (kind, name) values ('TEAM', ?) returning id",
                Long.class, "other-" + UUID.randomUUID().toString().substring(0, 8));
        jdbcTemplate.update(
                "insert into workspace_members (workspace_id, user_id, role) values (?, ?, 'OWNER')",
                otherWorkspaceId, owner.id());
        UUID otherPublicId = jdbcTemplate.queryForObject(
                "select public_id from workspaces where id = ?", UUID.class, otherWorkspaceId);

        // The same conflict an unheld collision gets. Saying it is reserved by
        // somebody else would tell a stranger who holds a name they cannot see.
        assertThatThrownBy(() -> service.create(owner,
                new CreateDnsDomainRequest("notyours", "pusan.dev", otherPublicId), "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("이미 사용 중");
    }

    @Test
    void releasingTwiceDoesNotRestartTheGrace() {
        DnsDomainView created = service.create(owner, request("twice"), "127.0.0.1");
        service.delete(owner, created.id(), "127.0.0.1");

        // Allowed, this holds a name out of the shared space for ever: delete it
        // once a month and the reservation never ends.
        assertThatThrownBy(() -> service.delete(owner, created.id(), "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("이미 해제한");
    }

    @Test
    void aReleasedNameTakesNoRecordEditsAndSaysSoAsAConflict() {
        DnsDomainView created = service.create(owner, request("noedit"), "127.0.0.1");
        service.delete(owner, created.id(), "127.0.0.1");

        // The records service guards the same thing as an invariant, which
        // answers 500. A name its owner already let go of is a state a request
        // can legitimately arrive in, so the answer is a conflict.
        assertThatThrownBy(() -> service.replaceRecords(owner, created.id(),
                new ReplaceDnsRecordSetsRequest(List.of(
                        new DesiredRecordSet("", DnsRecordType.A, List.of("93.184.216.34"), 300))),
                "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("이미 해제한");
        assertThatThrownBy(() -> service.renew(owner, created.id(), "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("이미 해제한");
    }

    @Test
    void aWorkspaceOwnerWithNoGrantMayStillReleaseTheName() {
        DnsDomainView created = service.create(owner, request("standing"), "127.0.0.1");
        // The person who issued it is gone: their grant is withdrawn and nobody
        // holds one. The workspace owner must still be able to take the name
        // back without first granting themselves access to it.
        jdbcTemplate.update("delete from resource_access_grants"
                + " where resource_type = 'DOMAIN'::resource_type");

        service.delete(owner, created.id(), "127.0.0.1");

        // Read from the row: managing is a standing right, but opening the
        // detail still needs a grant, and that split is the established one.
        assertThat(jdbcTemplate.queryForObject(
                "select released_at is not null from domains where public_id = ?",
                Boolean.class, created.id())).isTrue();
    }

    @Test
    void theRowCarriesTheRungItsReaderActsAt() {
        DnsDomainView created = service.create(owner, request("rung"), "127.0.0.1");
        long domainId = jdbcTemplate.queryForObject(
                "select id from domains where public_id = ?", Long.class, created.id());

        // The issuer holds OWNER, which is what the screen reads to decide
        // whether to draw a release button at all. Without it every reader sees
        // every action and learns from a refusal after pressing one.
        assertThat(created.myResourceRole()).isEqualTo(ResourceRole.OWNER);
        assertThat(queryService.get(owner, created.id()).myResourceRole())
                .isEqualTo(ResourceRole.OWNER);

        // Demote the one grant. The row stays open and the rung follows it down,
        // which is the case the screen exists to draw differently. Scoped to
        // this row: the suite shares one database and an unscoped update would
        // reach grants other classes seeded.
        jdbcTemplate.update("update resource_access_grants set role = 'VIEWER'::resource_role"
                + " where resource_type = 'DOMAIN'::resource_type and resource_id = ?", domainId);
        assertThat(queryService.get(owner, created.id()).myResourceRole())
                .isEqualTo(ResourceRole.VIEWER);

        // The listing answers the same rung. It is a separate code path — a
        // batch query rather than the single-resource one — and a list that
        // reported OWNER for everything would draw the same wrong buttons.
        assertThat(listed(owner, created.id()).myResourceRole()).isEqualTo(ResourceRole.VIEWER);
    }

    @Test
    void aListedRowReportsTheReadersOwnRungAndNobodyElses() {
        DnsDomainView created = service.create(owner, request("someoneelse"), "127.0.0.1");
        long domainId = jdbcTemplate.queryForObject(
                "select id from domains where public_id = ?", Long.class, created.id());

        // A second member of the same workspace, with no grant on this name.
        AuthenticatedUser stranger = member("stranger");

        DnsDomainView row = listed(stranger, created.id());
        // The row is listed so its existence and owner are visible, and the
        // absent rung is what says the inside is closed. Reporting the issuer's
        // OWNER here would contradict accessLimited on the same row, and a
        // screen reading the rung would draw every action for somebody the
        // server refuses.
        assertThat(row.accessLimited()).isTrue();
        assertThat(row.myResourceRole()).isNull();
        assertThat(row.ownerNames()).isNotEmpty();

        // A grant to the workspace as a whole opens the row for that member,
        // and the rung has to arrive with it — an open row reporting no rung
        // would have every action hidden from somebody entitled to them.
        jdbcTemplate.update("""
                insert into resource_access_grants
                       (resource_type, resource_id, grantee_type, role)
                values ('DOMAIN'::resource_type, ?, 'WORKSPACE'::access_grantee_type,
                        'MEMBER'::resource_role)
                """, domainId);
        DnsDomainView opened = listed(stranger, created.id());
        assertThat(opened.accessLimited()).isFalse();
        assertThat(opened.myResourceRole()).isEqualTo(ResourceRole.MEMBER);

        // Two grants reach the issuer now. They act at the higher of the two,
        // the same rule the single-resource form uses; taking the lower would
        // strip the issuer of their own name.
        assertThat(listed(owner, created.id()).myResourceRole()).isEqualTo(ResourceRole.OWNER);
    }

    /** The row for one name out of a reader's own listing. */
    private DnsDomainView listed(AuthenticatedUser actor, UUID domainId) {
        return queryService
                .listPage(actor, null, org.springframework.data.domain.PageRequest.of(0, 50))
                .getContent().stream()
                .filter(row -> row.id().equals(domainId))
                .findFirst().orElseThrow();
    }

    /** Another member of the seeded workspace, holding no grant on anything. */
    private AuthenticatedUser member(String prefix) {
        String email = prefix + "." + UUID.randomUUID().toString().substring(0, 8)
                + "@pusan.ac.kr";
        long id = createUser(email);
        jdbcTemplate.update(
                "insert into workspace_members (workspace_id, user_id, role) values (?, ?, 'MEMBER')",
                workspaceId, id);
        return new AuthenticatedUser(id,
                jdbcTemplate.queryForObject("select public_id from users where id = ?", UUID.class,
                        id),
                email, UserRole.USER, Map.of());
    }

    @Test
    void aRootWhoseOrganisationIsDisabledIssuesNothing() {
        jdbcTemplate.update("update orgs set status = 'DISABLED' where id = ?", orgId);

        // A name takes its organisation from the root, so issuing here would
        // attach it to one that is no longer taking anything on — the same
        // refusal a request form makes.
        assertThatThrownBy(() -> service.create(owner, request("disabled"), "127.0.0.1"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("발급할 수 없습니다");
    }

    private CreateDnsDomainRequest request(String label) {
        return new CreateDnsDomainRequest(label, "pusan.dev", workspacePublicId);
    }

    private long servedVm() {
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId,
                owner.id(), "어댑터 거절 확인", null, 1, 1024, 10);
        RequestFixtures.approveVmRequest(jdbcTemplate, requestId, owner.id(), null, 1, 1024, 10);
        int vmid = 911_000 + (int) (System.nanoTime() % 900);
        String host = "dds-vm-" + UUID.randomUUID().toString().substring(0, 10);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values ((select min(id) from nodes), ?, ?, ?, ?, ?,
                        (select min(id) from os_images), 1, 1024, 10, ?, 'RUNNING'::vm_status)
                returning id
                """, Long.class, workspaceId, orgId, requestId, host, host, vmid);
    }

    private long createUser(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", "도메인테스트");
            user.setRole(UserRole.USER);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        }).getId();
    }
}
