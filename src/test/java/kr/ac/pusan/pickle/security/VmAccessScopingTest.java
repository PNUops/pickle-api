package kr.ac.pusan.pickle.security;

import static kr.ac.pusan.pickle.support.AccessGrantFixtures.grantVmToOwningWorkspace;
import static kr.ac.pusan.pickle.support.AccessGrantFixtures.grantVmToUser;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import kr.ac.pusan.pickle.access.ResourceRole;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.ObjectMapper;

/**
 * Runtime enforcement of every resource-scoped operation, one row per op.
 *
 * <p>{@link PermissionMatrixTest} reads {@code @PreAuthorize} annotations and
 * compares them with {@code permission-matrix.yaml}. Resource-scoped ops carry
 * no annotation — the access list is consulted in the service layer — so that
 * comparison is vacuous for them: the matrix may declare
 * {@code allow_resource_scoped:EDITOR} while the code asks for VIEWER, and
 * nothing notices. This suite is the missing half. It drives each op over the
 * real HTTP surface with a requester whose standing is set precisely one rung
 * at a time, and asserts the answer.
 *
 * <p>Four rungs and two non-grant standings produce five scenarios per op:
 *
 * <ol>
 *   <li>outsider — the VM's existence is masked, so 404 and not 403;</li>
 *   <li>owning-workspace member with no grant — the VM is already listed to them,
 *       so an honest 403 {@code WORKSPACE_ROLE_INSUFFICIENT};</li>
 *   <li>a grant one rung below what the op needs — 403 (skipped for the
 *       VIEWER-rung ops, which sit on the floor);</li>
 *   <li>a grant at exactly the required rung — allowed;</li>
 *   <li>a workspace OWNER holding no grant at all — allowed only for the standing
 *       rights ({@link kr.ac.pusan.pickle.access.VmAccess#manages()}: deletion
 *       and the access list) and for the plain reads their standing VIEWER
 *       covers; refused for everything inside the VM.</li>
 * </ol>
 *
 * <p>"Allowed" is asserted as <em>neither 403 nor 404</em> rather than as a
 * success status. Past the access check most of these ops meet a state or
 * validation gate — the fixture VM is deliberately left in {@code CREATING},
 * which is inert for every power, publishing and password path — and a 409 or
 * 422 from that gate is itself the proof that authorization was passed. Pinning
 * a 200 would only couple this suite to each op's happy path.
 *
 * <p>Three traps decide whether a denial case means anything, and each is
 * disarmed here rather than trusted:
 *
 * <ul>
 *   <li>body validation can run before the access check, so every write is sent
 *       with a body the DTO accepts;</li>
 *   <li>sudo mode ({@code @RequireReauth}) answers 403 as well, which would
 *       make a denial pass for the wrong reason — the ops that need it are
 *       marked in the table and always carry a live token, and every expected
 *       403 additionally asserts the {@code WORKSPACE_ROLE_INSUFFICIENT} code so a
 *       {@code REAUTH_REQUIRED} can never be mistaken for it;</li>
 *   <li>the web terminal's kill switch is read before authorization and answers
 *       503, so it is switched on for every case.</li>
 * </ul>
 *
 * <p>Every (op, scenario) pair builds its own VM with its own domain, port
 * mapping, campus-IP request and spare access-list entry: several of these ops
 * destroy what they touch, and a shared fixture would make the outcome depend
 * on execution order.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmAccessScopingTest {

    /** Own proxmox_vmid range: the active-vmid unique index is global. */
    private static final AtomicInteger VMID_SEQ = new AtomicInteger(912_000);

    /** Public ports for the fixture mappings (unique per relay, per proto). */
    private static final AtomicInteger PUBLIC_PORT_SEQ = new AtomicInteger(40_000);

    /**
     * The ops a workspace owner reaches through their standing rights rather than
     * through a rung — {@code VmAccess.manages()}. This is the whole of that
     * standing: every other op in the table is refused to them, whatever its
     * rung, so a new op needs one table row and nothing else.
     */
    private static final Set<String> MANAGED_OPS = Set.of("deleteVm", "listVmAccessGrants",
            "addVmAccessGrant", "updateVmAccessGrant", "removeVmAccessGrant");

    /**
     * One resource-scoped operation as the permission matrix declares it.
     *
     * @param id       the contract operation id
     * @param method   HTTP method
     * @param path     path under {@code /api/v1}, with fixture placeholders
     * @param required the rung the matrix says this op needs
     * @param reauth   whether the endpoint is sudo-mode gated
     * @param body     request body template, or null for a bodiless request
     */
    private record ScopedOp(String id, HttpMethod method, String path, ResourceRole required,
            boolean reauth, String body) {
    }

    /** The five standings each op is driven with. */
    private enum Scenario {
        NON_MEMBER,
        MEMBER_WITHOUT_GRANT,
        GRANT_BELOW_RUNG,
        GRANT_AT_RUNG,
        WORKSPACE_OWNER_WITHOUT_GRANT
    }

    /** The throwaway resources one (op, scenario) pair acts on. */
    private record Fixture(long vmId, long domainId, long portForwardingId, long campusIpRequestId,
            long grantId, String label) {
    }

    /** Who is driving one case, and the reauth token they would need. */
    private record Requester(long userId, String token) {
    }

    /**
     * The 27 resource-scoped operations, transcribed from
     * {@code permission-matrix.yaml}. Adding an op to the product means adding
     * one row here.
     */
    private static final List<ScopedOp> OPS = List.of(
            op("getVm", HttpMethod.GET, "/vms/{vmId}", ResourceRole.VIEWER),
            op("listVmEvents", HttpMethod.GET, "/vms/{vmId}/events", ResourceRole.VIEWER),
            op("listVmPortForwardings", HttpMethod.GET, "/vms/{vmId}/port-forwardings",
                    ResourceRole.VIEWER),
            op("listVmCampusIpRequests", HttpMethod.GET, "/vms/{vmId}/campus-ip-requests",
                    ResourceRole.VIEWER),
            op("getDomain", HttpMethod.GET, "/domains/{domainId}", ResourceRole.VIEWER),

            op("startVm", HttpMethod.POST, "/vms/{vmId}/start", ResourceRole.MEMBER),
            op("shutdownVm", HttpMethod.POST, "/vms/{vmId}/shutdown", ResourceRole.MEMBER),
            op("rebootVm", HttpMethod.POST, "/vms/{vmId}/reboot", ResourceRole.MEMBER),
            op("forceStopVm", HttpMethod.POST, "/vms/{vmId}/force-stop", ResourceRole.MEMBER),
            op("createTerminalSession", HttpMethod.POST, "/vms/{vmId}/terminal-sessions",
                    ResourceRole.MEMBER),
            reauthOp("revealVmPassword", HttpMethod.GET, "/vms/{vmId}/password",
                    ResourceRole.MEMBER, null),

            op("getVmSettings", HttpMethod.GET, "/vms/{vmId}/settings", ResourceRole.EDITOR),
            reauthOp("updateVmSettings", HttpMethod.PATCH, "/vms/{vmId}/settings",
                    ResourceRole.EDITOR, "{\"settings\":{\"display_name\":\"스코프\"}}"),
            reauthOp("regenerateVmPassword", HttpMethod.POST, "/vms/{vmId}/password/regenerate",
                    ResourceRole.EDITOR, null),
            bodyOp("createVmDomain", HttpMethod.POST, "/vms/{vmId}/domains", ResourceRole.EDITOR,
                    "{\"port\":80,\"subdomain\":\"{label}\"}"),
            bodyOp("updateDomain", HttpMethod.PATCH, "/domains/{domainId}", ResourceRole.EDITOR,
                    "{\"port\":8080}"),
            op("deleteDomain", HttpMethod.DELETE, "/domains/{domainId}", ResourceRole.EDITOR),
            op("verifyDomain", HttpMethod.POST, "/domains/{domainId}/verify", ResourceRole.EDITOR),
            bodyOp("createVmPortForwarding", HttpMethod.POST, "/vms/{vmId}/port-forwardings",
                    ResourceRole.EDITOR, "{\"proto\":\"TCP\",\"targetPort\":22}"),
            op("deleteVmPortForwarding", HttpMethod.DELETE,
                    "/vms/{vmId}/port-forwardings/{portForwardingId}", ResourceRole.EDITOR),
            bodyOp("requestVmCampusIp", HttpMethod.POST, "/vms/{vmId}/campus-ip-requests",
                    ResourceRole.EDITOR, "{\"purpose\":\"스코프 테스트\",\"ports\":[80]}"),
            op("cancelVmCampusIpRequest", HttpMethod.DELETE,
                    "/vms/{vmId}/campus-ip-requests/{requestId}", ResourceRole.EDITOR),

            reauthOp("deleteVm", HttpMethod.DELETE, "/vms/{vmId}", ResourceRole.OWNER, null),
            op("listVmAccessGrants", HttpMethod.GET, "/vms/{vmId}/access", ResourceRole.OWNER),
            reauthOp("addVmAccessGrant", HttpMethod.POST, "/vms/{vmId}/access", ResourceRole.OWNER,
                    "{\"granteeType\":\"USER\",\"userId\":{spareUserId},\"role\":\"VIEWER\"}"),
            reauthOp("updateVmAccessGrant", HttpMethod.PATCH, "/vms/{vmId}/access/{grantId}",
                    ResourceRole.OWNER, "{\"role\":\"VIEWER\"}"),
            reauthOp("removeVmAccessGrant", HttpMethod.DELETE, "/vms/{vmId}/access/{grantId}",
                    ResourceRole.OWNER, null));

    private static ScopedOp op(String id, HttpMethod method, String path, ResourceRole required) {
        return new ScopedOp(id, method, path, required, false, null);
    }

    private static ScopedOp bodyOp(String id, HttpMethod method, String path,
            ResourceRole required, String body) {
        return new ScopedOp(id, method, path, required, false, body);
    }

    private static ScopedOp reauthOp(String id, HttpMethod method, String path,
            ResourceRole required, String body) {
        return new ScopedOp(id, method, path, required, true, body);
    }

    /** Every (op, scenario) pair; the below-rung case has no meaning at the floor. */
    private static Stream<Arguments> cases() {
        List<Arguments> arguments = new ArrayList<>();
        for (ScopedOp scopedOp : OPS) {
            for (Scenario scenario : Scenario.values()) {
                if (scenario == Scenario.GRANT_BELOW_RUNG
                        && scopedOp.required() == ResourceRole.VIEWER) {
                    continue;
                }
                arguments.add(Arguments.of(Named.of(scopedOp.id(), scopedOp), scenario));
            }
        }
        return arguments.stream();
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User workspaceOwner;
    private User member;
    private User listedBystander;
    private User spareBystander;
    private User outsider;
    private String workspaceOwnerToken;
    private String memberToken;
    private String outsiderToken;
    private long orgId;
    private long nodeId;
    private long imageId;
    private long workspaceId;
    private long relayId;

    @BeforeEach
    void setUp() {
        // The terminal's kill switch is read before authorization; left off, the
        // createTerminalSession rows would all answer 503 and prove nothing.
        jdbcTemplate.update("update settings set value = ?::jsonb where key = 'web_terminal_enabled'",
                "true");
        workspaceOwner = ensureUser("scope.owner@pusan.ac.kr", "범위워크스페이스소유자");
        member = ensureUser("scope.member@pusan.ac.kr", "범위구성원");
        listedBystander = ensureUser("scope.listed@pusan.ac.kr", "범위등재자");
        spareBystander = ensureUser("scope.spare@pusan.ac.kr", "범위예비자");
        outsider = ensureUser("scope.outsider@pusan.ac.kr", "범위외부인");
        workspaceOwnerToken = jwtService.createAccessToken(workspaceOwner);
        memberToken = jwtService.createAccessToken(member);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        workspaceId = ensureWorkspace();
        addMember(workspaceOwner.getId(), "OWNER");
        addMember(member.getId(), "MEMBER");
        addMember(listedBystander.getId(), "MEMBER");
        addMember(spareBystander.getId(), "MEMBER");
        // The outsider must stay out of the workspace: their whole purpose is the
        // 404 mask, which a stray membership row would turn into a 403.
        jdbcTemplate.update("delete from workspace_members where workspace_id = ? and user_id = ?",
                workspaceId, outsider.getId());
        relayId = ensureRelay();
    }

    @ParameterizedTest(name = "[{index}] {0} — {1}")
    @MethodSource("cases")
    void resourceScopedOpHonoursItsDeclaredRung(ScopedOp scopedOp, Scenario scenario)
            throws Exception {
        Fixture fixture = newFixture();
        Requester requester = standingFor(scopedOp, scenario, fixture);

        MockHttpServletResponse response = call(scopedOp, fixture, requester);
        String where = scopedOp.id() + " / " + scenario;

        if (scenario == Scenario.NON_MEMBER) {
            assertThat(response.getStatus()).as("%s: an outsider must not learn the VM exists",
                    where).isEqualTo(404);
            assertThat(errorCode(response)).as("%s: 404 error code", where)
                    .isEqualTo("RESOURCE_NOT_FOUND");
        } else if (allowed(scopedOp, scenario)) {
            // Neither denial status: what comes back instead (200, 202, 409,
            // 422, …) belongs to the op's own state machine, not to authz.
            assertThat(response.getStatus())
                    .as("%s: authorization must pass at the declared rung (body: %s)", where,
                            body(response))
                    .isNotIn(403, 404);
            assertThat(response.getStatus()).as("%s: passed authz but failed unexpectedly (%s)",
                    where, body(response)).isLessThan(500);
        } else {
            assertThat(response.getStatus()).as("%s: below the declared rung must be refused",
                    where).isEqualTo(403);
            // Pins the reason: a sudo-mode or generic denial answers 403 too,
            // and would otherwise let this case pass without the rung being
            // consulted at all.
            assertThat(errorCode(response)).as("%s: 403 error code", where)
                    .isEqualTo("WORKSPACE_ROLE_INSUFFICIENT");
        }
    }

    /**
     * The strongest grant wins. Someone named personally at VIEWER, in a workspace
     * the whole of which holds MEMBER, acts as a MEMBER — the two are combined,
     * not overridden by whichever is more specific.
     */
    @Test
    void personalAndWorkspaceWideGrantsCombineToTheStrongest() throws Exception {
        long vmId = insertVm();
        grantVmToUser(jdbcTemplate, vmId, member.getId(), "VIEWER");

        // The personal VIEWER grant alone is below the MEMBER rung startVm needs.
        MockHttpServletResponse viewerOnly = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/vms/" + vmId + "/start")
                                .header("Authorization", "Bearer " + memberToken))
                .andReturn().getResponse();
        assertThat(viewerOnly.getStatus()).isEqualTo(403);
        assertThat(errorCode(viewerOnly)).isEqualTo("WORKSPACE_ROLE_INSUFFICIENT");

        // Adding a workspace-wide MEMBER grant raises them; the personal VIEWER row
        // is still there and must not hold them down.
        grantVmToOwningWorkspace(jdbcTemplate, vmId, "MEMBER");
        MockHttpServletResponse combined = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/vms/" + vmId + "/start")
                                .header("Authorization", "Bearer " + memberToken))
                .andReturn().getResponse();
        assertThat(combined.getStatus()).as("the higher of the two grants decides (body: %s)",
                body(combined)).isNotIn(403, 404);
    }

    // ── driving one case ─────────────────────────────────────────────────────

    /** True when this standing should get past the access check for this op. */
    private static boolean allowed(ScopedOp scopedOp, Scenario scenario) {
        return switch (scenario) {
            case NON_MEMBER, MEMBER_WITHOUT_GRANT, GRANT_BELOW_RUNG -> false;
            case GRANT_AT_RUNG -> true;
            // A workspace owner's standing is exactly: manage the list and delete.
            // It is deliberately not a rung, so it does not even carry the
            // VIEWER reads — the VM's address, its guest account and its
            // published ports are inside, and inside needs a grant. What they
            // keep without one is knowing the VM exists, which is the listing's
            // limited view rather than any operation in this table.
            case WORKSPACE_OWNER_WITHOUT_GRANT -> MANAGED_OPS.contains(scopedOp.id());
        };
    }

    /** Writes the scenario's standing onto the fixture VM and names the caller. */
    private Requester standingFor(ScopedOp scopedOp, Scenario scenario, Fixture fixture) {
        switch (scenario) {
            case GRANT_BELOW_RUNG -> grantVmToUser(jdbcTemplate, fixture.vmId(), member.getId(),
                    oneRungBelow(scopedOp.required()).name());
            case GRANT_AT_RUNG -> grantVmToUser(jdbcTemplate, fixture.vmId(), member.getId(),
                    scopedOp.required().name());
            default -> {
                // The other three standings are the absence of a grant.
            }
        }
        return switch (scenario) {
            case NON_MEMBER -> new Requester(outsider.getId(), outsiderToken);
            case WORKSPACE_OWNER_WITHOUT_GRANT -> new Requester(workspaceOwner.getId(), workspaceOwnerToken);
            default -> new Requester(member.getId(), memberToken);
        };
    }

    /** OWNER → EDITOR → MEMBER → VIEWER; never called at the floor. */
    private static ResourceRole oneRungBelow(ResourceRole role) {
        ResourceRole[] rungs = ResourceRole.values();
        return rungs[role.ordinal() + 1];
    }

    private MockHttpServletResponse call(ScopedOp scopedOp, Fixture fixture, Requester requester)
            throws Exception {
        URI uri = URI.create("/api/v1" + resolve(scopedOp.path(), fixture));
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders
                .request(scopedOp.method(), uri)
                .header("Authorization", "Bearer " + requester.token());
        if (scopedOp.reauth()) {
            request = request.header(ReauthTestSupport.HEADER,
                    ReauthTestSupport.seededReauthHeader(jdbcTemplate, requester.userId()));
        }
        if (scopedOp.body() != null) {
            request = request.contentType(MediaType.APPLICATION_JSON)
                    .content(resolve(scopedOp.body(), fixture));
        }
        return mockMvc.perform(request).andReturn().getResponse();
    }

    /** Fills the fixture's ids into a path or body template. */
    private String resolve(String template, Fixture fixture) {
        return template
                .replace("{vmId}", String.valueOf(fixture.vmId()))
                .replace("{domainId}", String.valueOf(fixture.domainId()))
                .replace("{portForwardingId}", String.valueOf(fixture.portForwardingId()))
                .replace("{requestId}", String.valueOf(fixture.campusIpRequestId()))
                .replace("{grantId}", String.valueOf(fixture.grantId()))
                .replace("{spareUserId}", String.valueOf(spareBystander.getId()))
                .replace("{label}", fixture.label());
    }

    private String errorCode(MockHttpServletResponse response) throws Exception {
        return objectMapper.readTree(body(response)).get("code").asString();
    }

    private String body(MockHttpServletResponse response) throws Exception {
        response.setCharacterEncoding("UTF-8");
        return response.getContentAsString();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /**
     * A whole disposable world for one case: the VM plus every sub-resource an
     * op addresses by id, so that a denial is never a 404 for a missing child
     * and an allowed call is never decided by one either.
     */
    private Fixture newFixture() {
        long vmId = insertVm();
        String label = "sc" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long domainId = jdbcTemplate.queryForObject("""
                insert into domains (vm_id, kind, fqdn, root_domain, status)
                values (?, 'PLATFORM'::domain_kind, ?, 'pusan.dev', 'ACTIVE'::domain_status)
                returning id
                """, Long.class, vmId, label + ".pusan.dev");
        long portForwardingId = jdbcTemplate.queryForObject("""
                insert into port_mappings (relay_id, vm_id, proto, public_port, target_port,
                                           status, last_change_generation, created_by)
                values (?, ?, 'TCP', ?, 8080, 'ACTIVE', 0, ?)
                returning id
                """, Long.class, relayId, vmId, PUBLIC_PORT_SEQ.incrementAndGet(),
                workspaceOwner.getId());
        long campusIpRequestId = jdbcTemplate.queryForObject("""
                insert into campus_ip_requests (vm_id, requested_by, purpose, ports)
                values (?, ?, '접근 범위 테스트', '[80]'::jsonb)
                returning id
                """, Long.class, vmId, workspaceOwner.getId());
        // The {grantId} target is deliberately a USER row for somebody else: a
        // workspace-wide row here would raise the requester's own rung and quietly
        // turn the below-rung scenarios into passes.
        grantVmToUser(jdbcTemplate, vmId, listedBystander.getId(), "VIEWER");
        long grantId = jdbcTemplate.queryForObject("""
                select id from resource_access_grants
                 where resource_type = 'VM' and resource_id = ? and grantee_type = 'USER'
                   and user_id = ?
                """, Long.class, vmId, listedBystander.getId());
        return new Fixture(vmId, domainId, portForwardingId, campusIpRequestId, grantId, label);
    }

    /**
     * Inserted straight into the table, so no approval ever granted anyone: the
     * access list starts empty and each scenario writes exactly the standing it
     * means to test. CREATING is chosen on purpose — every power, publishing,
     * terminal and password path refuses that state <em>after</em> the access
     * check, so an authorized call ends in a harmless 409 instead of driving a
     * real transition on a fixture the next case would inherit.
     */
    private long insertVm() {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (workspace_id, org_id, requester_id, purpose, image_id,
                                         req_vcpu, req_memory_mb, req_disk_gb)
                values (?, ?, ?, '접근 범위 테스트', ?, 1, 1024, 10)
                returning id
                """, Long.class, workspaceId, orgId, workspaceOwner.getId(), imageId);
        String hostname = "scope-" + UUID.randomUUID().toString().substring(0, 12);
        return jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, 'CREATING'::vm_status)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                imageId, VMID_SEQ.incrementAndGet());
    }

    private long ensureWorkspace() {
        return jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name, slug)
                values ('TEAM'::workspace_kind, '접근 범위 테스트 팀', 'vm-access-scoping')
                on conflict (slug) where deleted_at is null
                    do update set name = excluded.name
                returning id
                """, Long.class);
    }

    private void addMember(long userId, String role) {
        jdbcTemplate.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, ?::workspace_member_role)
                on conflict (workspace_id, user_id) do update set role = excluded.role
                """, workspaceId, userId, role);
    }

    /**
     * The relay the fixture port mappings hang off. Kept disabled: nothing here
     * exercises allocation, and an enabled relay is picked up by the allocation
     * path of any other suite sharing this database.
     */
    private long ensureRelay() {
        return jdbcTemplate.queryForObject("""
                insert into relays (name, source_ip, port_band_start, port_band_end,
                                    public_host, enabled)
                values ('vm-access-scoping', '203.0.113.77', 40000, 41000,
                        'relay.example.com', false)
                on conflict (name) do update set enabled = false
                returning id
                """, Long.class);
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            user.setRole(UserRole.USER);
            return userRepository.save(user);
        });
    }
}
