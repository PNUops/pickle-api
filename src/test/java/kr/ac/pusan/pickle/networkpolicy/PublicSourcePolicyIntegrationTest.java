package kr.ac.pusan.pickle.networkpolicy;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.publishing.RouteApplyJob;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.AccessGrantFixtures;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/** Source-policy activation, authorization, revision and fail-closed producer coverage. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class PublicSourcePolicyIntegrationTest {

    private static final AtomicInteger VMID = new AtomicInteger(970_000);
    private static WireMockServer agent;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        agent = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        agent.start();
        registry.add("pickle.proxy-agent.base-url", () -> "http://localhost:" + agent.port());
        registry.add("pickle.proxy-agent.token", () -> "source-policy-test-token");
        registry.add("pickle.network-policy.enabled", () -> "true");
        registry.add("jobrunr.background-job-server.enabled", () -> "false");
    }

    @AfterAll
    static void stopAgent() {
        agent.stop();
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired RouteApplyJob routeApply;
    @Autowired SourcePolicyActivationService activation;
    @Autowired SourcePolicyStore store;

    private User owner;
    private User editor;
    private User viewer;
    private String ownerToken;
    private String editorToken;
    private String viewerToken;
    private long workspaceId;
    private long orgId;

    @BeforeEach
    void setUp() {
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/status"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"routes\":[],\"certs\":[],"
                                + "\"capabilities\":[\"source-acl-v1\"]}")));
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/apply"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"applied\":true}")));
        owner = user("source.owner@pusan.ac.kr", "정책 소유자");
        editor = user("source.editor@pusan.ac.kr", "정책 편집자");
        viewer = user("source.viewer@pusan.ac.kr", "정책 열람자");
        ownerToken = jwt.createAccessToken(owner);
        editorToken = jwt.createAccessToken(editor);
        viewerToken = jwt.createAccessToken(viewer);
        orgId = SeedFixtures.seedOrgId(jdbc);
        workspaceId = jdbc.queryForObject("""
                insert into workspaces (kind, name) values ('PROJECT', ?) returning id
                """, Long.class, "정책 테스트 " + UUID.randomUUID());
        member(owner.getId(), "OWNER");
        member(editor.getId(), "MEMBER");
        member(viewer.getId(), "MEMBER");
    }

    @Test
    void endpointNormalizesHostsAndRejectsStaleRevisionByVmRole() throws Exception {
        Fixture f = servedDomain();
        getPolicy(f.domainPublicId(), viewerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.explicit").value(false))
                .andExpect(jsonPath("$.applyState").value("PENDING"));
        putPolicy(f.domainPublicId(), viewerToken, 0, List.of("192.0.2.1"))
                .andExpect(status().isForbidden());
        putPolicy(f.domainPublicId(), editorToken, 0,
                List.of("192.0.2.1", "2001:db8::/32"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.allowedCidrs[0]").value("192.0.2.1/32"));
        putPolicy(f.domainPublicId(), ownerToken, 0, List.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_POLICY_REVISION_CONFLICT"));
    }

    @Test
    void memberAndOrgAdminEntryPointsRecordDistinctInterventionMarkers() throws Exception {
        Fixture f = servedDomain();
        putPolicy(f.domainPublicId(), ownerToken, 0, List.of("192.0.2.0/24"))
                .andExpect(status().isAccepted());
        assertThat(auditAdminIntervention("domain.source_policy_update",
                f.domainPublicId())).isFalse();

        // This account begins as a global USER and reaches the admin surface only
        // after receiving the organisation role represented by the effective rung.
        assertThat(owner.getRole()).isEqualTo(UserRole.USER);
        SeedFixtures.grantOrgRole(jdbc, owner.getId(), orgId, UserRole.ORG_ADMIN);
        owner.setRole(UserRole.ORG_ADMIN);
        owner = users.saveAndFlush(owner);
        String orgAdminToken = jwt.createAccessToken(owner);
        UUID routePublicId = SeedFixtures.publicId(jdbc, "routes", f.routeId());
        mockMvc.perform(put("/api/v1/admin/routes/" + routePublicId + "/source-policy")
                        .header("Authorization", "Bearer " + orgAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", 1,
                                "allowedCidrs", List.of("198.51.100.0/24")))))
                .andExpect(status().isAccepted());
        assertThat(auditAdminIntervention("domain.source_policy_update",
                f.domainPublicId())).isTrue();
    }

    @Test
    void unrelatedUserCannotReadOrWriteAnotherVmsDomainPolicy() throws Exception {
        Fixture f = servedDomain();
        User outsider = user("source.outsider@pusan.ac.kr", "정책 외부 사용자");
        String outsiderToken = jwt.createAccessToken(outsider);

        getPolicy(f.domainPublicId(), outsiderToken).andExpect(status().isNotFound());
        putPolicy(f.domainPublicId(), outsiderToken, 0, List.of("192.0.2.0/24"))
                .andExpect(status().isNotFound());
    }

    @Test
    void policySurvivesRouteRemovalAndRepublishForTheSameDomain() throws Exception {
        Fixture f = servedDomain();
        putPolicy(f.domainPublicId(), ownerToken, 0, List.of("192.0.2.0/24"))
                .andExpect(status().isAccepted());
        jdbc.update("delete from routes where id = ?", f.routeId());
        long replacementRoute = jdbc.queryForObject("""
                insert into routes (domain_id, target_port, status, generation)
                values (?, 8081, 'PENDING', nextval('route_generation_seq')) returning id
                """, Long.class, f.domainId());

        getPolicy(f.domainPublicId(), ownerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.explicit").value(true))
                .andExpect(jsonPath("$.allowedCidrs[0]").value("192.0.2.0/24"));
        assertThat(replacementRoute).isPositive();
    }

    @Test
    void legacyAppliedRouteGetsNewGenerationBeforeDefaultDenyIsConfirmed() {
        Fixture f = servedDomain();
        jdbc.update("""
                update routes set status = 'APPLIED', applied_generation = generation,
                    source_policy_generation = null where id = ?
                """, f.routeId());
        long oldGeneration = generation(f.routeId());

        assertThat(activation.activateRoutes()).isPositive();
        long armedGeneration = generation(f.routeId());
        assertThat(armedGeneration).isGreaterThan(oldGeneration);
        assertThat(jdbc.queryForObject("select status from routes where id = ?",
                String.class, f.routeId())).isEqualTo("PENDING");
        assertThat(activation.activateRoutes()).isZero();
        assertThat(generation(f.routeId())).isEqualTo(armedGeneration);

        routeApply.apply(f.routeId());
        agent.verify(postRequestedFor(urlPathEqualTo("/apply"))
                .withRequestBody(matchingJsonPath("$.sourcePolicy.allowedCidrs",
                        equalToJson("[]", true, true))));
        assertThat(jdbc.queryForObject("select status from routes where id = ?",
                String.class, f.routeId())).isEqualTo("APPLIED");
    }

    @Test
    void proxyCapabilityLossLeavesPolicyPendingAndSendsNoApply() throws Exception {
        Fixture f = servedDomain();
        putPolicy(f.domainPublicId(), ownerToken, 0, List.of()).andExpect(status().isAccepted());
        agent.resetRequests();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/status"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"routes\":[],\"certs\":[],\"capabilities\":[]}")));
        assertThatThrownBy(() -> routeApply.apply(f.routeId()))
                .isInstanceOf(RuntimeException.class);
        agent.verify(0, postRequestedFor(urlPathEqualTo("/apply")));
        getPolicy(f.domainPublicId(), ownerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.applyState").value("PENDING"))
                .andExpect(jsonPath("$.lastError").isNotEmpty());

        long pendingGeneration = generation(f.routeId());
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/status"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"routes\":[],\"certs\":[],"
                                + "\"capabilities\":[\"source-acl-v1\"]}")));
        routeApply.apply(f.routeId());
        assertThat(generation(f.routeId())).isEqualTo(pendingGeneration);
        getPolicy(f.domainPublicId(), ownerToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.applyState").value("APPLIED"))
                .andExpect(jsonPath("$.lastError").doesNotExist());
    }

    @Test
    void dnsOnlyDomainIsNotAPublicSourcePolicyTarget() throws Exception {
        long domainId = jdbc.queryForObject("""
                insert into domains (workspace_id, org_id, kind, fqdn, root_domain, status,
                                     renew_due_at)
                values (?, ?, 'EXTERNAL', ?, 'pusan.dev', 'ACTIVE', now() + interval '30 days')
                returning id
                """, Long.class, workspaceId, orgId,
                "dns-only-" + UUID.randomUUID().toString().substring(0, 8) + ".pusan.dev");
        getPolicy(SeedFixtures.publicId(jdbc, "domains", domainId), ownerToken)
                .andExpect(status().isNotFound());
    }

    @Test
    void firstRevisionHasOneWinnerUnderConcurrency() throws Exception {
        Fixture f = servedDomain();
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> store.replaceDomain(f.domainId(), 0,
                    List.of("192.0.2.0/24"), owner.getId()));
            var second = pool.submit(() -> store.replaceDomain(f.domainId(), 0,
                    List.of("198.51.100.0/24"), editor.getId()));
            int successes = 0;
            int conflicts = 0;
            for (var future : List.of(first, second)) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    successes++;
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof kr.ac.pusan.pickle.common.error.ApiException) {
                        conflicts++;
                    } else {
                        throw e;
                    }
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private Fixture servedDomain() {
        long requestId = RequestFixtures.insertVmRequest(jdbc, workspaceId, orgId, owner.getId(),
                "출발지 정책 테스트", null, 1, 1024, 10);
        String name = "source-" + UUID.randomUUID().toString().substring(0, 8);
        long vmId = jdbc.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values ((select min(id) from nodes), ?, ?, ?, ?, ?,
                        (select min(id) from os_images), 1, 1024, 10, ?, 'RUNNING') returning id
                """, Long.class, workspaceId, orgId, requestId, name, name, VMID.incrementAndGet());
        long allocation = jdbc.queryForObject("""
                insert into ip_allocations (pool_id, ip, vm_id, status)
                values ((select id from ip_pools where name='guest-private'), ?::inet, ?, 'ALLOCATED')
                returning id
                """, Long.class, "172.29.230." + (VMID.get() % 200 + 1), vmId);
        jdbc.update("update vms set ip_allocation_id = ? where id = ?", allocation, vmId);
        AccessGrantFixtures.grantVmToUser(jdbc, vmId, owner.getId(), "OWNER");
        AccessGrantFixtures.grantVmToUser(jdbc, vmId, editor.getId(), "EDITOR");
        AccessGrantFixtures.grantVmToUser(jdbc, vmId, viewer.getId(), "VIEWER");
        long domainId = jdbc.queryForObject("""
                insert into domains (workspace_id, org_id, vm_id, kind, fqdn, root_domain, status)
                values (?, ?, ?, 'PLATFORM', ?, 'pusan.dev', 'ACTIVE') returning id
                """, Long.class, workspaceId, orgId, vmId, name + ".pusan.dev");
        long routeId = jdbc.queryForObject("""
                insert into routes (domain_id, target_port, status, generation)
                values (?, 8080, 'PENDING', nextval('route_generation_seq')) returning id
                """, Long.class, domainId);
        return new Fixture(domainId, SeedFixtures.publicId(jdbc, "domains", domainId), routeId);
    }

    private ResultActions getPolicy(UUID domainId, String token) throws Exception {
        return mockMvc.perform(get("/api/v1/domains/" + domainId + "/source-policy")
                .header("Authorization", "Bearer " + token));
    }

    private ResultActions putPolicy(UUID domainId, String token, long revision,
            List<String> cidrs) throws Exception {
        return mockMvc.perform(put("/api/v1/domains/" + domainId + "/source-policy")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("expectedRevision", revision, "allowedCidrs", cidrs))));
    }

    private long generation(long routeId) {
        return jdbc.queryForObject("select generation from routes where id = ?",
                Long.class, routeId);
    }

    private boolean auditAdminIntervention(String action, UUID targetId) {
        return jdbc.queryForObject("""
                select (detail ->> 'adminIntervention')::boolean
                  from audit_logs where action = ? and target_id = ?
                 order by id desc limit 1
                """, Boolean.class, action, targetId.toString());
    }

    private User user(String email, String name) {
        User user = users.findByEmail(email)
                .orElseGet(() -> new User(email, "{test-no-login}", name));
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user.setRole(UserRole.USER);
        User saved = users.saveAndFlush(user);
        jdbc.update("delete from user_org_roles where user_id = ?", saved.getId());
        return saved;
    }

    private void member(long userId, String role) {
        jdbc.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, ?::workspace_member_role)
                """, workspaceId, userId, role);
    }

    private record Fixture(long domainId, UUID domainPublicId, long routeId) { }
}
