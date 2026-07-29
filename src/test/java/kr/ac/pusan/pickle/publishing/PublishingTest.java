package kr.ac.pusan.pickle.publishing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.provisioning.DeleteVmJob;
import kr.ac.pusan.pickle.publishing.agent.ProxyAgentUnreachableException;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.ReauthTestSupport;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.VmStatus;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * HTTP publishing per contract v0.4.0: publish/update/unpublish authz,
 * the SSRF guard (target forced to the VM's own IP), port-22 and custom-domain
 * platform-zone rejection, the route PENDING→APPLIED apply against a WireMock
 * proxy-agent (incl. stale-generation no-op), custom-domain DNS verification via
 * a stub resolver, and admin route/domain/certificate scoping. JobRunr's
 * background server is disabled so job effects are observed by calling the job
 * directly (deterministic, same style as the power tests).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, PublishingTest.StubDnsConfig.class})
class PublishingTest {

    private static final String APPLY_PATH = "/apply";
    private static final AtomicInteger IP_SEQ = new AtomicInteger(1);
    private static final AtomicInteger VMID_SEQ = new AtomicInteger(950_000);

    private static WireMockServer agent;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        agent = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        agent.start();
        registry.add("pickle.proxy-agent.base-url", () -> "http://localhost:" + agent.port());
        registry.add("pickle.proxy-agent.token", () -> "test-agent-token");
        // Deterministic job effects: drive jobs directly, no background runner.
        registry.add("jobrunr.background-job-server.enabled", () -> "false");
    }

    @AfterAll
    static void stopAgent() {
        agent.stop();
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
    @Autowired
    private RouteApplyJob routeApplyJob;
    @Autowired
    private ResyncRoutesJob resyncRoutesJob;
    @Autowired
    private RouteReconcileJob routeReconcileJob;
    @Autowired
    private DeleteVmJob deleteVmJob;
    @Autowired
    private DomainVerifier domainVerifier;
    @Autowired
    private StubDnsResolver dns;

    private User owner;
    private String ownerToken;
    private String editorToken;
    private String viewerToken;
    private String outsiderToken;
    private String orgAdminToken;
    private String sysAdminToken;
    private long orgId;
    private long nodeId;
    private long templateId;
    private long groupId;
    private String groupSlug;

    @BeforeEach
    void setUp() throws Exception {
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(okApply(1)));
        dns.clear();

        owner = ensureUser("pub.owner@pusan.ac.kr", "공개소유자", UserRole.USER, null);
        User editor = ensureUser("pub.manager@pusan.ac.kr", "공개매니저", UserRole.USER, null);
        User viewer = ensureUser("pub.viewer@pusan.ac.kr", "공개뷰어", UserRole.USER, null);
        User outsider = ensureUser("pub.outsider@pusan.ac.kr", "공개외부인", UserRole.USER, null);
        User orgAdmin = userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow();
        User sysAdmin = userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow();
        ownerToken = jwtService.createAccessToken(owner);
        editorToken = jwtService.createAccessToken(editor);
        viewerToken = jwtService.createAccessToken(viewer);
        outsiderToken = jwtService.createAccessToken(outsider);
        orgAdminToken = jwtService.createAccessToken(orgAdmin);
        sysAdminToken = jwtService.createAccessToken(sysAdmin);

        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        templateId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        groupSlug = "pub-" + UUID.randomUUID().toString().substring(0, 8);
        groupId = createTeam(groupSlug);
        addMember(groupId, editor.getEmail(), "EDITOR");
        addMember(groupId, viewer.getEmail(), "VIEWER");
    }

    // ── authorization ────────────────────────────────────────────────────────

    @Test
    void publishAuthorizesByGroupRole() throws Exception {
        long vmId = publishableVm("team-alpha", "pickle.pnuops.com", VmStatus.RUNNING);

        // non-member → 404 (existence masked)
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/publish")
                        .header("Authorization", "Bearer " + outsiderToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"port\":8080}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        // VIEWER → 403
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/publish")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"port\":8080}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        // EDITOR → 202
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/publish")
                        .header("Authorization", "Bearer " + editorToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"port\":8080}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fqdn").value("team-alpha.pickle.pnuops.com"))
                .andExpect(jsonPath("$.domain.kind").value("REQUESTED"))
                .andExpect(jsonPath("$.route.status").value("PENDING"))
                .andExpect(jsonPath("$.route.targetPort").value(8080));
    }

    // ── validation ─────────────────────────────────────────────────────────

    @Test
    void publishRejectsWhenNoSubdomainAnywhere() throws Exception {
        long vmId = publishableVm(null, null, VmStatus.RUNNING);
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/publish")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("subdomain"));
    }

    @Test
    void publishBodySubdomainOverridesRequestFormValue() throws Exception {
        long vmId = publishableVm("team-form", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80,\"subdomain\":\"team-body\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fqdn").value("team-body.pickle.pnuops.com"))
                .andExpect(jsonPath("$.domain.kind").value("REQUESTED"));
    }

    @Test
    void publishRejectsReservedSubdomain() throws Exception {
        long vmId = publishableVm(null, "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80,\"subdomain\":\"portal\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("subdomain"));
    }

    @Test
    void publishRevalidatesTheStoredRequestFormSubdomain() throws Exception {
        // A name stored on the request row before the reserved list grew must
        // be refused when the publish falls back to it — the stored-value
        // branch runs the same policy as the body branch.
        long vmId = publishableVm("portal", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("subdomain"));
    }

    @Test
    void publishRejectsProfanitySubdomain() throws Exception {
        long vmId = publishableVm(null, "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80,\"subdomain\":\"team-shit-lab\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("subdomain"));
    }

    @Test
    void publishRejectsUnknownRootDomain() throws Exception {
        long vmId = publishableVm(null, null, VmStatus.RUNNING);
        publish(vmId, "{\"port\":80,\"subdomain\":\"team-root\",\"rootDomain\":\"evil.example.com\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("rootDomain"));
    }

    @Test
    void publishRejectsSubdomainAndCustomDomainTogether() throws Exception {
        long vmId = publishableVm("team-both", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80,\"subdomain\":\"team-both2\","
                + "\"customDomain\":\"both.example.com\"}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("subdomain"));
    }

    @Test
    void unpublishFreesTheNameForAnotherVm() throws Exception {
        long first = publishableVm("team-reuse", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(first, "{\"port\":80}").andExpect(status().isAccepted());
        // Taken while live.
        long second = publishableVm(null, "pickle.pnuops.com", VmStatus.RUNNING);
        publish(second, "{\"port\":80,\"subdomain\":\"team-reuse\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_FQDN_TAKEN"));

        mockMvc.perform(delete("/api/v1/vms/" + first + "/publication")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());

        // The partial unique index only covers non-REMOVED rows — the name is free.
        publish(second, "{\"port\":80,\"subdomain\":\"team-reuse\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fqdn").value("team-reuse.pickle.pnuops.com"));
    }

    @Test
    void publishRejectsPort22() throws Exception {
        long vmId = publishableVm("team-ssh", "pickle.pnuops.com", VmStatus.RUNNING);
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/publish")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"port\":22}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("port"));
    }

    @Test
    void publishRejectsCustomDomainUnderPlatformZone() throws Exception {
        long vmId = publishableVm("team-custom", "pickle.pnuops.com", VmStatus.RUNNING);
        mockMvc.perform(post("/api/v1/vms/" + vmId + "/publish")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"port\":80,\"customDomain\":\"squat.pickle.pnuops.com\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("customDomain"));
    }

    @Test
    void doublePublishConflicts() throws Exception {
        long vmId = publishableVm("team-dup", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80}").andExpect(status().isAccepted());
        publish(vmId, "{\"port\":80}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PUBLICATION_ALREADY_EXISTS"));
    }

    // ── SSRF + apply ──────────────────────────────────────────────────────────

    @Test
    void applyForcesTargetToVmOwnIpAndMarksApplied() throws Exception {
        long vmId = publishableVm("team-apply", "pickle.pnuops.com", VmStatus.RUNNING);
        String vmIp = vmIp(vmId);
        publish(vmId, "{\"port\":8080}").andExpect(status().isAccepted());
        long routeId = routeIdForVm(vmId);
        assertThat(routeStatus(routeId)).isEqualTo("PENDING");

        routeApplyJob.apply(routeId);

        // SSRF: the agent was asked for the VM's OWN allocated IP, never a client value.
        agent.verify(postRequestedFor(urlPathEqualTo(APPLY_PATH))
                .withRequestBody(matchingJsonPath("$.targetIp", com.github.tomakehurst.wiremock.client.WireMock.equalTo(vmIp)))
                .withRequestBody(matchingJsonPath("$.desiredState",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("PRESENT")))
                .withRequestBody(matchingJsonPath("$.targetPort",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("8080")))
                .withRequestBody(matchingJsonPath("$.certRef",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("origin-wildcard"))));
        assertThat(routeStatus(routeId)).isEqualTo("APPLIED");
    }

    @Test
    void staleGenerationIsNoOp() throws Exception {
        long vmId = publishableVm("team-stale", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80}").andExpect(status().isAccepted());
        long routeId = routeIdForVm(vmId);
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"applied\":false,\"generation\":99}")));

        routeApplyJob.apply(routeId);

        // 409 = superseded → the job leaves the route PENDING (no false APPLIED/FAILED).
        assertThat(routeStatus(routeId)).isEqualTo("PENDING");
    }

    @Test
    void applyFailureRecordsFailedWithStderr() throws Exception {
        long vmId = publishableVm("team-fail", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80}").andExpect(status().isAccepted());
        long routeId = routeIdForVm(vmId);
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"applied\":false,\"error\":\"nginx: [emerg] boom\"}")));

        routeApplyJob.apply(routeId);

        assertThat(routeStatus(routeId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("select last_error from routes where id = ?",
                String.class, routeId)).contains("boom");
    }

    // ── custom-domain verification ──────────────────────────────────────────

    @Test
    void customDomainPublishStaysPendingUntilDnsVerified() throws Exception {
        long vmId = publishableVm("team-cd", "pickle.pnuops.com", VmStatus.RUNNING);
        String fqdn = "app." + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        publish(vmId, "{\"port\":3000,\"customDomain\":\"" + fqdn + "\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.domain.kind").value("CUSTOM"))
                .andExpect(jsonPath("$.domain.status").value("PENDING"))
                .andExpect(jsonPath("$.domain.verification.token").isNotEmpty());
        long domainId = domainIdForVm(vmId);
        String token = jdbcTemplate.queryForObject(
                "select verification_token from domains where id = ?", String.class, domainId);

        // DNS not set yet → stays VERIFYING
        domainVerifier.verifyOne(domainId);
        assertThat(domainStatus(domainId)).isEqualTo("VERIFYING");

        // Publish the required TXT + A → verified ACTIVE, route to apply reported
        dns.setTxt("_pickle-verify." + fqdn, List.of(token));
        dns.setA(fqdn, List.of("164.125.249.87"));
        assertThat(domainVerifier.verifyOne(domainId)).isPresent();
        assertThat(domainStatus(domainId)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("select verified_at from domains where id = ?",
                Instant.class, domainId)).isNotNull();
    }

    @Test
    void verifyTriggerIsRateLimitedAndDeduplicated() throws Exception {
        long vmId = publishableVm("team-vrl", "pickle.pnuops.com", VmStatus.RUNNING);
        String fqdn = "rl." + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        publish(vmId, "{\"port\":3000,\"customDomain\":\"" + fqdn + "\"}")
                .andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);
        // publish enqueued the initial verify job — it is still queued (no
        // background server), so further triggers must not stack duplicates
        long enqueued = verifyJobCount();

        try {
            mockMvc.perform(post("/api/v1/domains/" + domainId + "/verify")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isAccepted());
            mockMvc.perform(post("/api/v1/domains/" + domainId + "/verify")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isAccepted());
            assertThat(verifyJobCount()).isEqualTo(enqueued);

            // per-user sliding window (10/min): the 11th trigger is rejected
            for (int i = 0; i < 8; i++) {
                mockMvc.perform(post("/api/v1/domains/" + domainId + "/verify")
                                .header("Authorization", "Bearer " + ownerToken))
                        .andExpect(status().isAccepted());
            }
            mockMvc.perform(post("/api/v1/domains/" + domainId + "/verify")
                            .header("Authorization", "Bearer " + ownerToken))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        } finally {
            // keep the shared per-user counter from leaking into other tests
            jdbcTemplate.update("delete from auth_rate_limits where scope = 'domain_verify'");
        }
    }

    @Test
    void unverifiedDomainParksFailedPastDeadline() throws Exception {
        long vmId = publishableVm("team-vto", "pickle.pnuops.com", VmStatus.RUNNING);
        String fqdn = "to." + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        publish(vmId, "{\"port\":3000,\"customDomain\":\"" + fqdn + "\"}")
                .andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);

        // within the 72h window a miss keeps polling (VERIFYING) …
        domainVerifier.verifyOne(domainId);
        assertThat(domainStatus(domainId)).isEqualTo("VERIFYING");

        // … past it the domain parks FAILED (recurring scan skips FAILED)
        jdbcTemplate.update(
                "update domains set created_at = now() - interval '4 days' where id = ?", domainId);
        domainVerifier.verifyOne(domainId);
        assertThat(domainStatus(domainId)).isEqualTo("FAILED");

        // a manual re-check still succeeds once the records finally match
        verifyDns(domainId, fqdn);
        assertThat(domainVerifier.verifyOne(domainId)).isPresent();
        assertThat(domainStatus(domainId)).isEqualTo("ACTIVE");
    }

    // ── cert confirmation via agent /status + verify re-trigger ─────────────

    @Test
    void customDomainCertActivatesOnlyOnAgentConfirmedOk() throws Exception {
        long vmId = publishableVm("team-certok", "pickle.pnuops.com", VmStatus.RUNNING);
        String fqdn = "ok." + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        publish(vmId, "{\"port\":3000,\"customDomain\":\"" + fqdn + "\"}")
                .andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);
        verifyDns(domainId, fqdn);
        long routeId = domainVerifier.verifyOne(domainId).orElseThrow();
        stubStatus(fqdn, "OK", null);

        routeApplyJob.apply(routeId);

        assertThat(routeStatus(routeId)).isEqualTo("APPLIED");
        assertThat(certStatus(domainId)).isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject("""
                select not_after from certificates where domain_id = ? and status <> 'REVOKED'
                """, Instant.class, domainId)).isNotNull();
    }

    @Test
    void certFailureOnAgentStatusMarksFailedAndVerifyRetriggersWithBumpedGeneration()
            throws Exception {
        long vmId = publishableVm("team-certfail", "pickle.pnuops.com", VmStatus.RUNNING);
        String fqdn = "ko." + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        publish(vmId, "{\"port\":3000,\"customDomain\":\"" + fqdn + "\"}")
                .andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);
        verifyDns(domainId, fqdn);
        long routeId = domainVerifier.verifyOne(domainId).orElseThrow();

        // The agent answers /apply 200 even when certbot failed — the failure is
        // only on GET /status, and the cert must NOT go ACTIVE with a fabricated
        // notAfter.
        stubStatus(fqdn, "FAILED", "acme: challenge failed");
        routeApplyJob.apply(routeId);
        assertThat(routeStatus(routeId)).isEqualTo("APPLIED");
        assertThat(certStatus(domainId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject("""
                select last_error from certificates where domain_id = ? and status <> 'REVOKED'
                """, String.class, domainId)).contains("challenge");
        assertThat(jdbcTemplate.queryForObject("""
                select not_after from certificates where domain_id = ? and status <> 'REVOKED'
                """, Instant.class, domainId)).isNull();
        long appliedGen = jdbcTemplate.queryForObject(
                "select applied_generation from routes where id = ?", Long.class, routeId);

        // a verify retry re-arms the FAILED cert and bumps the route
        // generation past the applied one, so the agent re-runs certbot instead
        // of rejecting the re-apply as stale (409).
        long retryRouteId = domainVerifier.verifyOne(domainId).orElseThrow();
        assertThat(retryRouteId).isEqualTo(routeId);
        assertThat(certStatus(domainId)).isEqualTo("RENEWING");
        assertThat(routeStatus(routeId)).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject("select generation from routes where id = ?",
                Long.class, routeId)).isGreaterThan(appliedGen);

        stubStatus(fqdn, "OK", null);
        routeApplyJob.apply(routeId);
        assertThat(certStatus(domainId)).isEqualTo("ACTIVE");

        // Fully settled (route APPLIED + cert ACTIVE) → nothing left to re-apply.
        assertThat(domainVerifier.verifyOne(domainId)).isEmpty();
    }

    // ── admin scoping ────────────────────────────────────────────────────────

    @Test
    void adminRoutesAreOrgScoped() throws Exception {
        long vmId = publishableVm("team-admin", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80}").andExpect(status().isAccepted());

        // ORG_ADMIN of the seed org sees the route; a regular user cannot reach the admin list.
        mockMvc.perform(get("/api/v1/admin/routes").header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.vmId == %d)]".formatted(vmId)).exists());
        mockMvc.perform(get("/api/v1/admin/routes").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
        // Admin domains + certificates render (wildcard cert is visible to all admins).
        mockMvc.perform(get("/api/v1/admin/domains").header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.vmId == %d)]".formatted(vmId)).exists());
        mockMvc.perform(get("/api/v1/admin/certificates")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.kind == 'ORIGIN_CA_WILDCARD')]").exists());
    }

    @Test
    void resyncIsSysAdminOnly() throws Exception {
        mockMvc.perform(post("/api/v1/admin/routes/resync")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/routes/resync")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void resyncValidationFailureLeavesRouteStatusUntouched() throws Exception {
        long vmId = publishableVm("team-rsfail", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80}").andExpect(status().isAccepted());
        long routeId = routeIdForVm(vmId);
        routeApplyJob.apply(routeId);
        assertThat(routeStatus(routeId)).isEqualTo("APPLIED");

        // 422 sync-all changed NOTHING on the agent — the healthy route must
        // not be flipped FAILED.
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock
                .post(urlPathEqualTo("/sync-all"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"applied\":false,\"error\":\"nginx: [emerg] boom\"}")));
        resyncRoutesJob.run();
        assertThat(routeStatus(routeId)).isEqualTo("APPLIED");
    }

    @Test
    void adminDomainRemovedFilterAndFailedCertHideExpiry() throws Exception {
        long vmId = publishableVm("team-admrm", "pickle.pnuops.com", VmStatus.RUNNING);
        String fqdn = "adm." + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        publish(vmId, "{\"port\":80,\"customDomain\":\"" + fqdn + "\"}")
                .andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);
        mockMvc.perform(delete("/api/v1/domains/" + domainId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        assertThat(domainStatus(domainId)).isEqualTo("REMOVED");

        // status=REMOVED surfaces the row; the default listing keeps hiding it.
        mockMvc.perform(get("/api/v1/admin/domains?status=REMOVED")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(domainId)).exists());
        mockMvc.perform(get("/api/v1/admin/domains")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == %d)]".formatted(domainId)).doesNotExist());

        // a FAILED cert reports no expiry countdown even with a stale notAfter
        long certId = jdbcTemplate.queryForObject("""
                update certificates set status = 'FAILED', not_after = now() + interval '10 days'
                 where domain_id = ? returning id
                """, Long.class, domainId);
        String body = mockMvc.perform(get("/api/v1/admin/certificates?status=FAILED")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        tools.jackson.databind.JsonNode cert = null;
        for (tools.jackson.databind.JsonNode node : objectMapper.readTree(body).get("content")) {
            if (node.get("id").asLong() == certId) {
                cert = node;
            }
        }
        assertThat(cert).isNotNull();
        assertThat(cert.get("daysUntilExpiry").isNull()).isTrue();
    }

    // ── transport-failure retry + recurring reconcile (hardening) ───────────

    /**
     * Marks every existing route confirmed so a reconcile-cycle test only sees
     * the routes it creates itself (the recurring job scans the whole shared
     * test DB, where earlier tests leave unconfirmed REMOVED rows behind).
     */
    private void settleAllRoutes() {
        jdbcTemplate.update("""
                update routes set applied_generation = generation
                 where applied_generation is null or applied_generation < generation
                """);
    }

    @Test
    void unpublishWithUnreachableAgentIsReconciledToAbsent() throws Exception {
        settleAllRoutes();
        long vmId = publishableVm("team-recon", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":8080}").andExpect(status().isAccepted());
        long routeId = routeIdForVm(vmId);
        routeApplyJob.apply(routeId);
        assertThat(routeStatus(routeId)).isEqualTo("APPLIED");

        // Agent goes dark, then the user unpublishes: the DB flips to REMOVED
        // (tombstone/UI say "unpublished") while nginx would keep serving.
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        mockMvc.perform(delete("/api/v1/vms/" + vmId + "/publication")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        assertThat(routeStatus(routeId)).isEqualTo("REMOVED");

        // The enqueued apply hits the transport failure: it records the error,
        // keeps the desired REMOVED state, and throws so JobRunr retries it.
        assertThatThrownBy(() -> routeApplyJob.apply(routeId))
                .isInstanceOf(ProxyAgentUnreachableException.class);
        assertThat(routeStatus(routeId)).isEqualTo("REMOVED");
        assertThat(jdbcTemplate.queryForObject("select last_error from routes where id = ?",
                String.class, routeId)).contains("연결 실패");

        // Agent back up. Within the settle grace the reconciler leaves the route
        // to its own retry …
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(okApply(100_000)));
        routeReconcileJob.run();
        agent.verify(0, postRequestedFor(urlPathEqualTo(APPLY_PATH)));

        // … past the grace it re-pushes ABSENT and the removal is confirmed.
        jdbcTemplate.update(
                "update routes set updated_at = now() - interval '5 minutes' where id = ?", routeId);
        routeReconcileJob.run();
        agent.verify(postRequestedFor(urlPathEqualTo(APPLY_PATH))
                .withRequestBody(matchingJsonPath("$.desiredState",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("ABSENT"))));
        assertThat(jdbcTemplate.queryForObject("""
                select applied_generation >= generation from routes where id = ?
                """, Boolean.class, routeId)).isTrue();

        // Confirmed → the next cycle has nothing left to push.
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(okApply(100_001)));
        jdbcTemplate.update(
                "update routes set updated_at = now() - interval '5 minutes' where id = ?", routeId);
        routeReconcileJob.run();
        agent.verify(0, postRequestedFor(urlPathEqualTo(APPLY_PATH)));
    }

    @Test
    void publishWithUnreachableAgentStaysPendingAndIsReconciledToPresent() throws Exception {
        settleAllRoutes();
        long vmId = publishableVm("team-recon2", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":8080}").andExpect(status().isAccepted());
        long routeId = routeIdForVm(vmId);
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        // Transport failure is NOT a FAILED verdict: the route stays PENDING
        // (retryable), never falsely "rejected".
        assertThatThrownBy(() -> routeApplyJob.apply(routeId))
                .isInstanceOf(ProxyAgentUnreachableException.class);
        assertThat(routeStatus(routeId)).isEqualTo("PENDING");

        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(okApply(100_002)));
        jdbcTemplate.update(
                "update routes set updated_at = now() - interval '5 minutes' where id = ?", routeId);
        routeReconcileJob.run();
        agent.verify(postRequestedFor(urlPathEqualTo(APPLY_PATH))
                .withRequestBody(matchingJsonPath("$.desiredState",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("PRESENT"))));
        assertThat(routeStatus(routeId)).isEqualTo("APPLIED");
    }

    @Test
    void reconcilerSkipsConfirmedAndAgentRejectedRoutes() throws Exception {
        settleAllRoutes();
        // Confirmed APPLIED route: nothing to reconcile.
        long appliedVm = publishableVm("team-recon3", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(appliedVm, "{\"port\":80}").andExpect(status().isAccepted());
        long appliedRoute = routeIdForVm(appliedVm);
        routeApplyJob.apply(appliedRoute);
        assertThat(routeStatus(appliedRoute)).isEqualTo("APPLIED");

        // 422-FAILED route: a definitive agent verdict — re-pushing the same
        // config every cycle would thrash; recovery stays re-publish/resync.
        long failedVm = publishableVm("team-recon4", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(failedVm, "{\"port\":80}").andExpect(status().isAccepted());
        long failedRoute = routeIdForVm(failedVm);
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"applied\":false,\"error\":\"nginx: [emerg] boom\"}")));
        routeApplyJob.apply(failedRoute);
        assertThat(routeStatus(failedRoute)).isEqualTo("FAILED");

        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(okApply(100_003)));
        jdbcTemplate.update("update routes set updated_at = now() - interval '5 minutes'"
                + " where id in (?, ?)", appliedRoute, failedRoute);
        routeReconcileJob.run();
        agent.verify(0, postRequestedFor(urlPathEqualTo(APPLY_PATH)));
    }

    @Test
    void unreachableAgentOnTeardownBlocksDeletionAndIpRelease() throws Exception {
        long vmId = publishableVm("team-delnet", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":8080}").andExpect(status().isAccepted());
        routeApplyJob.apply(routeIdForVm(vmId));
        mockMvc.perform(delete("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                .andExpect(status().isAccepted());
        jdbcTemplate.update("update vms set proxmox_vmid = null where id = ?", vmId);
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        deleteVmJob.deleteVm(vmId);

        // TRANSPORT (agent unreachable) must block IP release exactly like a 422:
        // the vhost removal is unconfirmed either way.
        assertThat(jdbcTemplate.queryForObject("select status from vms where id = ?",
                String.class, vmId)).isEqualTo("DELETING");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from ip_allocations where vm_id = ? and status = 'ALLOCATED'
                """, Long.class, vmId)).isEqualTo(1);
    }

    @Test
    void unpublishRemovesRoute() throws Exception {
        long vmId = publishableVm("team-unpub", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80}").andExpect(status().isAccepted());
        long routeId = routeIdForVm(vmId);
        mockMvc.perform(delete("/api/v1/vms/" + vmId + "/publication")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        assertThat(routeStatus(routeId)).isEqualTo("REMOVED");
        // Platform domain row is cleaned up (REMOVED).
        assertThat(domainStatus(domainIdForVm(vmId))).isEqualTo("REMOVED");
    }

    @Test
    void customDomainUnpublishLeavesVmCleanlyRepublishable() throws Exception {
        long vmId = publishableVm("team-tomb", "pickle.pnuops.com", VmStatus.RUNNING);
        String fqdn = "app." + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        publish(vmId, "{\"port\":3000,\"customDomain\":\"" + fqdn + "\"}")
                .andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);
        String token = jdbcTemplate.queryForObject(
                "select verification_token from domains where id = ?", String.class, domainId);

        mockMvc.perform(delete("/api/v1/vms/" + vmId + "/publication")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        // The custom row survives as a tombstone (verification state preserved) …
        assertThat(domainStatus(domainId)).isEqualTo("PENDING");
        // … but the VM is NOT published: detail shows publication null (the
        // contract requires PublicationView.route — no route-less publication).
        mockMvc.perform(get("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publication").value((Object) null));
        // Re-unpublish and PATCH answer 404 (contract: unpublished VM).
        mockMvc.perform(delete("/api/v1/vms/" + vmId + "/publication")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/vms/" + vmId + "/publication")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"port\":8080}"))
                .andExpect(status().isNotFound());

        // Re-publish of the SAME custom FQDN revives the tombstone — no 409, the
        // row (and its verification token) is reused.
        publish(vmId, "{\"port\":3000,\"customDomain\":\"" + fqdn + "\"}")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fqdn").value(fqdn));
        assertThat(domainIdForVm(vmId)).isEqualTo(domainId);
        assertThat(jdbcTemplate.queryForObject(
                "select verification_token from domains where id = ?", String.class, domainId))
                .isEqualTo(token);
        assertThat(routeStatus(routeIdForVm(vmId))).isEqualTo("PENDING");
    }

    @Test
    void platformPublishAfterCustomUnpublishRetiresTombstone() throws Exception {
        long vmId = publishableVm("team-tomb2", "pickle.pnuops.com", VmStatus.RUNNING);
        String fqdn = "app." + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        publish(vmId, "{\"port\":3000,\"customDomain\":\"" + fqdn + "\"}")
                .andExpect(status().isAccepted());
        long tombstoneId = domainIdForVm(vmId);
        mockMvc.perform(delete("/api/v1/vms/" + vmId + "/publication")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());

        // Publishing a different target (platform subdomain) retires the tombstone.
        publish(vmId, "{\"port\":80}").andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fqdn").value("team-tomb2.pickle.pnuops.com"));
        assertThat(domainStatus(tombstoneId)).isEqualTo("REMOVED");
        assertThat(domainIdForVm(vmId)).isNotEqualTo(tombstoneId);
    }

    @Test
    void portOnlyUpdateReappliesSameDomain() throws Exception {
        long vmId = publishableVm("team-port", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80}").andExpect(status().isAccepted());
        long routeId = routeIdForVm(vmId);
        long genBefore = jdbcTemplate.queryForObject("select generation from routes where id = ?",
                Long.class, routeId);
        mockMvc.perform(patch("/api/v1/vms/" + vmId + "/publication")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"port\":3000}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.route.targetPort").value(3000));
        long genAfter = jdbcTemplate.queryForObject("select generation from routes where id = ?",
                Long.class, routeId);
        assertThat(genAfter).isGreaterThan(genBefore);
    }

    // ── VM deletion tears down publishing ───────────────────────────────────

    @Test
    void deletePipelineTearsDownPublishingBeforeIpRelease() throws Exception {
        long vmId = publishableVm("team-delpub", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":8080}").andExpect(status().isAccepted());
        long routeId = routeIdForVm(vmId);
        long domainId = domainIdForVm(vmId);
        routeApplyJob.apply(routeId);
        assertThat(routeStatus(routeId)).isEqualTo("APPLIED");
        long appliedGen = jdbcTemplate.queryForObject(
                "select applied_generation from routes where id = ?", Long.class, routeId);

        // Self-delete accepted, then run the destroy pipeline directly. The vmid
        // is nulled so the (unstubbed) Proxmox destroy step skips, and the
        // grace deadline is fast-forwarded — the pipeline refuses undue intents.
        mockMvc.perform(delete("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                .andExpect(status().isAccepted());
        jdbcTemplate.update("""
                update vms set proxmox_vmid = null,
                       delete_scheduled_for = now() - interval '1 minute' where id = ?
                """, vmId);
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(okApply(999)));

        deleteVmJob.deleteVm(vmId);

        // The FQDN's vhost was removed (ABSENT, bumped generation) before the
        // IP was released and the VM marked DELETED.
        agent.verify(postRequestedFor(urlPathEqualTo(APPLY_PATH))
                .withRequestBody(matchingJsonPath("$.desiredState",
                        com.github.tomakehurst.wiremock.client.WireMock.equalTo("ABSENT")))
                .withRequestBody(matchingJsonPath("$.fqdn",
                        com.github.tomakehurst.wiremock.client.WireMock
                                .equalTo("team-delpub.pickle.pnuops.com"))));
        assertThat(routeStatus(routeId)).isEqualTo("REMOVED");
        assertThat(domainStatus(domainId)).isEqualTo("REMOVED");
        assertThat(jdbcTemplate.queryForObject("select generation from routes where id = ?",
                Long.class, routeId)).isGreaterThan(appliedGen);
        assertThat(jdbcTemplate.queryForObject("select status from vms where id = ?",
                String.class, vmId)).isEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from ip_allocations where vm_id = ? and status = 'ALLOCATED'
                """, Long.class, vmId)).isZero();
    }

    @Test
    void failedTeardownBlocksDeletionAndIpRelease() throws Exception {
        long vmId = publishableVm("team-delfail", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":8080}").andExpect(status().isAccepted());
        routeApplyJob.apply(routeIdForVm(vmId));
        mockMvc.perform(delete("/api/v1/vms/" + vmId)
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken)))
                .andExpect(status().isAccepted());
        jdbcTemplate.update("""
                update vms set proxmox_vmid = null,
                       delete_scheduled_for = now() - interval '1 minute' where id = ?
                """, vmId);
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"applied\":false,\"error\":\"nginx: [emerg] boom\"}")));

        deleteVmJob.deleteVm(vmId);

        // Vhost removal unconfirmed → the pipeline retries instead of releasing
        // the IP under a live route (no silent stale vhost).
        assertThat(jdbcTemplate.queryForObject("select status from vms where id = ?",
                String.class, vmId)).isEqualTo("DELETING");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from ip_allocations where vm_id = ? and status = 'ALLOCATED'
                """, Long.class, vmId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select status from provisioning_tasks
                 where vm_id = ? and kind = 'DELETE' order by id desc limit 1
                """, String.class, vmId)).isEqualTo("RETRYING");

        // Agent recovers → the retried run completes the deletion.
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo(APPLY_PATH))
                .willReturn(okApply(1000)));
        deleteVmJob.deleteVm(vmId);
        assertThat(jdbcTemplate.queryForObject("select status from vms where id = ?",
                String.class, vmId)).isEqualTo("DELETED");
    }

    // ── admin post-hoc intervention (contract v0.18.0) ───────────────────────

    @Test
    void adminForceReleaseTearsDownLikeUserDeletion() throws Exception {
        long vmId = publishableVm("team-force", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80}").andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);
        long routeId = routeIdForVm(vmId);
        long generationBefore = routeGeneration(routeId);

        mockMvc.perform(post("/api/v1/admin/domains/" + domainId + "/force-release")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());

        assertThat(domainStatus(domainId)).isEqualTo("REMOVED");
        assertThat(routeStatus(routeId)).isEqualTo("REMOVED");
        assertThat(routeGeneration(routeId)).isGreaterThan(generationBefore);
        assertThat(auditCount("domain.force_release", domainId)).isEqualTo(1);

        // an already-REMOVED domain answers the same 404 as an unknown id
        mockMvc.perform(post("/api/v1/admin/domains/" + domainId + "/force-release")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminDomainInterventionIsOrgScopedWithThe404Mask() throws Exception {
        long vmId = publishableVm("team-scope", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80}").andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);
        long routeId = routeIdForVm(vmId);

        long otherOrgId = jdbcTemplate.queryForObject("""
                insert into orgs (name, slug) values ('개입 타기관', 'pub-other-org')
                on conflict (slug) do update set name = excluded.name returning id
                """, Long.class);
        User otherOrgAdmin = ensureUser("pub.other.admin@pusan.ac.kr", "타기관관리자",
                UserRole.ORG_ADMIN, otherOrgId);
        String otherToken = jwtService.createAccessToken(otherOrgAdmin);

        mockMvc.perform(post("/api/v1/admin/domains/" + domainId + "/force-release")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/admin/routes/" + routeId + "/apply")
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        assertThat(domainStatus(domainId)).isNotEqualTo("REMOVED");

        // a plain user is refused by the role gate
        mockMvc.perform(post("/api/v1/admin/domains/" + domainId + "/force-release")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminVerifyTriggersWithoutUserRateLimitAndRejectsPlatformDomains() throws Exception {
        long vmId = publishableVm("team-averify", "pickle.pnuops.com", VmStatus.RUNNING);
        String fqdn = "av." + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        publish(vmId, "{\"port\":3000,\"customDomain\":\"" + fqdn + "\"}")
                .andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);

        mockMvc.perform(post("/api/v1/admin/domains/" + domainId + "/verify")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isAccepted());
        assertThat(auditCount("domain.admin_verify", domainId)).isEqualTo(1);
        // no per-user rate-limit row is consumed by the admin trigger
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from auth_rate_limits where scope = 'domain_verify'
                """, Long.class)).isZero();

        long platformVm = publishableVm("team-avplat", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(platformVm, "{\"port\":80}").andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/admin/domains/" + domainIdForVm(platformVm) + "/verify")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_NOT_CUSTOM"));
    }

    @Test
    void adminRouteApplyRefusesUnverifiedCustomDomains() throws Exception {
        // Regression (2026-07-28 review): the single-route re-apply must honor
        // the "unverified custom domain never goes live" invariant that the
        // resync and the unconfirmed-route sweep enforce.
        long vmId = publishableVm("team-unver", "pickle.pnuops.com", VmStatus.RUNNING);
        String fqdn = "uv." + UUID.randomUUID().toString().substring(0, 8) + ".example.com";
        publish(vmId, "{\"port\":3000,\"customDomain\":\"" + fqdn + "\"}")
                .andExpect(status().isAccepted());
        long routeId = routeIdForVm(vmId);
        long generationBefore = routeGeneration(routeId);

        mockMvc.perform(post("/api/v1/admin/routes/" + routeId + "/apply")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DOMAIN_NOT_ACTIVE"));
        // untouched: no generation bump, no audit, route stays PENDING
        assertThat(routeGeneration(routeId)).isEqualTo(generationBefore);
        assertThat(auditCount("route.apply", routeId)).isZero();
        assertThat(routeStatus(routeId)).isEqualTo("PENDING");
    }

    @Test
    void adminRouteApplyBumpsGenerationAndRepushesRemoval() throws Exception {
        long vmId = publishableVm("team-radm", "pickle.pnuops.com", VmStatus.RUNNING);
        publish(vmId, "{\"port\":80}").andExpect(status().isAccepted());
        long routeId = routeIdForVm(vmId);
        long domainId = domainIdForVm(vmId);
        long generationBefore = routeGeneration(routeId);

        mockMvc.perform(post("/api/v1/admin/routes/" + routeId + "/apply")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isAccepted());
        assertThat(routeGeneration(routeId)).isGreaterThan(generationBefore);
        assertThat(routeStatus(routeId)).isEqualTo("PENDING");
        assertThat(auditCount("route.apply", routeId)).isEqualTo(1);

        // a REMOVED route re-pushes its removal (desired state), status stays REMOVED
        mockMvc.perform(post("/api/v1/admin/domains/" + domainId + "/force-release")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk());
        long generationAfterRelease = routeGeneration(routeId);
        mockMvc.perform(post("/api/v1/admin/routes/" + routeId + "/apply")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isAccepted());
        assertThat(routeStatus(routeId)).isEqualTo("REMOVED");
        assertThat(routeGeneration(routeId)).isGreaterThan(generationAfterRelease);
    }

    private long routeGeneration(long routeId) {
        return jdbcTemplate.queryForObject("select generation from routes where id = ?",
                Long.class, routeId);
    }

    private long auditCount(String action, long targetId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = ? and target_id = ?",
                Long.class, action, targetId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions publish(long vmId, String body)
            throws Exception {
        return mockMvc.perform(post("/api/v1/vms/" + vmId + "/publish")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private long routeIdForVm(long vmId) {
        return jdbcTemplate.queryForObject("""
                select r.id from routes r join domains d on d.id = r.domain_id
                 where d.vm_id = ? order by r.id desc limit 1
                """, Long.class, vmId);
    }

    private long domainIdForVm(long vmId) {
        return jdbcTemplate.queryForObject(
                "select id from domains where vm_id = ? order by id desc limit 1", Long.class, vmId);
    }

    private String routeStatus(long routeId) {
        return jdbcTemplate.queryForObject("select status from routes where id = ?", String.class, routeId);
    }

    private String domainStatus(long domainId) {
        return jdbcTemplate.queryForObject("select status from domains where id = ?", String.class,
                domainId);
    }

    private long verifyJobCount() {
        return jdbcTemplate.queryForObject("""
                select count(*) from jobrunr_jobs
                 where jobsignature like '%DomainVerificationJob.verify(%'
                """, Long.class);
    }

    private String certStatus(long domainId) {
        return jdbcTemplate.queryForObject(
                "select status from certificates where domain_id = ? and status <> 'REVOKED'",
                String.class, domainId);
    }

    /** Points the stub DNS at the required TXT + A so the next check verifies. */
    private void verifyDns(long domainId, String fqdn) {
        String token = jdbcTemplate.queryForObject(
                "select verification_token from domains where id = ?", String.class, domainId);
        dns.setTxt("_pickle-verify." + fqdn, List.of(token));
        dns.setA(fqdn, List.of("164.125.249.87"));
    }

    /** Stubs the agent GET /status with a single cert entry for the FQDN. */
    private void stubStatus(String fqdn, String state, String error) {
        String cert = "{\"fqdn\":\"" + fqdn + "\",\"state\":\"" + state
                + "\",\"checkedAt\":\"2026-07-12T00:00:00Z\""
                + (error != null ? ",\"error\":\"" + error + "\"" : "") + "}";
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo("/status"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"health\":\"ok\",\"routes\":[],\"certs\":[" + cert + "]}")));
    }

    private String vmIp(long vmId) {
        return jdbcTemplate.queryForObject("""
                select host(a.ip) from ip_allocations a
                 join vms v on v.ip_allocation_id = a.id where v.id = ?
                """, String.class, vmId);
    }

    /**
     * Inserts an approved+running VM with an ALLOCATED IP. The request-form
     * subdomain/root are the publish-time defaults (self-service publishing);
     * null means the publish body must carry the name or answer 422.
     */
    private long publishableVm(String desiredSubdomain, String rootDomain, VmStatus status) {
        long requestId = jdbcTemplate.queryForObject("""
                insert into vm_requests (group_id, org_id, requester_id, purpose, image_id,
                                         req_vcpu, req_memory_mb, req_disk_gb,
                                         desired_subdomain, root_domain)
                values (?, ?, ?, '공개 테스트', ?, 1, 1024, 10, ?, ?)
                returning id
                """, Long.class, groupId, orgId, owner.getId(), templateId,
                desiredSubdomain, rootDomain);
        jdbcTemplate.update("""
                insert into vm_request_reviews (request_id, reviewer_id, decision,
                        granted_vcpu, granted_memory_mb, granted_disk_gb, granted_image_id)
                values (?, ?, 'APPROVE'::review_decision, 1, 1024, 10, ?)
                """, requestId, owner.getId(), templateId);
        String hostname = "pub-" + UUID.randomUUID().toString().substring(0, 12);
        int vmid = VMID_SEQ.incrementAndGet();
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, group_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, ?::vm_status)
                returning id
                """, Long.class, nodeId, groupId, orgId, requestId, hostname, hostname,
                templateId, vmid, status.name());
        String ip = "172.29.200." + IP_SEQ.getAndIncrement();
        long allocId = jdbcTemplate.queryForObject("""
                insert into ip_allocations (pool_id, ip, vm_id, status)
                values ((select id from ip_pools where name = 'guest-private'), ?::inet, ?, 'ALLOCATED')
                returning id
                """, Long.class, ip, vmId);
        jdbcTemplate.update("update vms set ip_allocation_id = ? where id = ?", allocId, vmId);
        return vmId;
    }

    private long createTeam(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/groups")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", "공개 테스트 " + slug, "slug", slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Sudo-mode gate: mint the caller's X-Reauth-Token for the protected call. */
    private String reauth(String token) {
        return ReauthTestSupport.seededReauthFor(jdbcTemplate, jwtService, token);
    }

    private void addMember(long groupId, String email, String role) throws Exception {
        mockMvc.perform(post("/api/v1/groups/" + groupId + "/members")
                        .header("Authorization", "Bearer " + ownerToken)
                        .header(ReauthTestSupport.HEADER, reauth(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "role", role))))
                .andExpect(status().isCreated());
    }

    private User ensureUser(String email, String name, UserRole role, Long userOrgId) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            user.setRole(role);
            if (userOrgId != null) {
                user.setOrgId(userOrgId);
            }
            return userRepository.save(user);
        });
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okApply(long gen) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"applied\":true,\"generation\":" + gen + "}");
    }

    /** Deterministic DNS for verification tests (overrides the JNDI resolver). */
    static class StubDnsResolver implements DnsResolver {
        private final Map<String, List<String>> txt = new ConcurrentHashMap<>();
        private final Map<String, List<String>> a = new ConcurrentHashMap<>();

        void clear() {
            txt.clear();
            a.clear();
        }

        void setTxt(String name, List<String> values) {
            txt.put(name, values);
        }

        void setA(String name, List<String> values) {
            a.put(name, values);
        }

        @Override
        public List<String> txtRecords(String name) {
            return txt.getOrDefault(name, List.of());
        }

        @Override
        public List<String> aRecords(String name) {
            return a.getOrDefault(name, List.of());
        }
    }

    @TestConfiguration
    static class StubDnsConfig {
        @Bean
        @Primary
        StubDnsResolver stubDnsResolver() {
            return new StubDnsResolver();
        }
    }
}
