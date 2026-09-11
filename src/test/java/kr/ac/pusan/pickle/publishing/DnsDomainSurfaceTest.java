package kr.ac.pusan.pickle.publishing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
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
