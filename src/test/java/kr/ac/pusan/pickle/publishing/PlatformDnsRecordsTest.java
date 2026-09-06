package kr.ac.pusan.pickle.publishing;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kr.ac.pusan.pickle.config.DnsProperties;
import kr.ac.pusan.pickle.config.PublishingProperties;
import kr.ac.pusan.pickle.publishing.agent.ApplyOutcome;
import kr.ac.pusan.pickle.publishing.dns.RecordingDnsRecordProvider;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.settings.SettingsService;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The platform's own A records across the domain lifecycle, driven through
 * the real endpoints and jobs against a recording provider: attach ensures
 * the record before the vhost, release removes it after, revive puts it
 * back, VM deletion and admin force-release take it down, a failed removal
 * keeps the route unconfirmed until the sweeper's reclaim finishes it, a
 * failed ensure holds the route for the reconciler, the admin resync adds
 * what is missing and prunes only true orphans, custom domains never reach
 * the provider, and an unconfigured provider refuses platform publishing.
 * The proxy-agent is WireMock; its calls and the provider's land in one
 * sequence so their order can be asserted.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({EmbeddedPostgresConfig.class, PublishingTest.StubDnsConfig.class,
        PlatformDnsRecordsTest.RecordingDnsConfig.class})
class PlatformDnsRecordsTest {

    private static final String ROOT = "pusan.dev";
    // Every suite in the shared embedded PG needs its OWN proxmox_vmid base
    // (vms_proxmox_vmid_active_uq is global): pick an unused range by grepping
    // VMID_SEQ across src/test before adding one.
    private static final AtomicInteger VMID_SEQ = new AtomicInteger(990_000);
    private static final AtomicInteger IP_SEQ = new AtomicInteger(1);

    /** Provider steps and agent applies, in the order they happened. */
    private static final List<String> SEQUENCE = new CopyOnWriteArrayList<>();
    private static final Pattern FQDN = Pattern.compile("\"fqdn\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern STATE = Pattern.compile("\"desiredState\"\\s*:\\s*\"([A-Z]+)\"");

    /** Appends each agent apply to {@link #SEQUENCE} as it is served. */
    public static class SequenceListener
            implements com.github.tomakehurst.wiremock.extension.ServeEventListener {
        @Override
        public String getName() {
            return "dns-sequence";
        }

        @Override
        public void beforeResponseSent(com.github.tomakehurst.wiremock.stubbing.ServeEvent event,
                com.github.tomakehurst.wiremock.extension.Parameters parameters) {
            if (!event.getRequest().getUrl().startsWith("/apply")) {
                return;
            }
            String body = event.getRequest().getBodyAsString();
            Matcher fqdn = FQDN.matcher(body);
            Matcher state = STATE.matcher(body);
            SEQUENCE.add("agent:" + (state.find() ? state.group(1).toLowerCase(java.util.Locale.ROOT)
                    : "?") + ":" + (fqdn.find() ? fqdn.group(1) : "?"));
        }
    }

    @TestConfiguration
    static class RecordingDnsConfig {
        @Bean
        @Primary
        RecordingDnsRecordProvider recordingDnsRecordProvider() {
            return new RecordingDnsRecordProvider(SEQUENCE);
        }
    }

    private static WireMockServer agent;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // The stand-in answers with the generation it was asked to apply, as
        // the real agent does: a stub that always confirmed generation 1 would
        // leave every route unconfirmed and the assertions below meaningless.
        agent = new WireMockServer(WireMockConfiguration.options().dynamicPort()
                .globalTemplating(true).extensions(new SequenceListener()));
        agent.start();
        registry.add("pickle.proxy-agent.base-url", () -> "http://localhost:" + agent.port());
        registry.add("pickle.proxy-agent.token", () -> "test-agent-token");
        registry.add("jobrunr.background-job-server.enabled", () -> "false");
        // The prune half of the resync is exercised here; the dry-run half is
        // driven through a hand-built PlatformDnsRecords below.
        registry.add("pickle.dns.prune-orphans", () -> "true");
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
    private RouteReconcileJob routeReconcileJob;
    @Autowired
    private ResyncRoutesJob resyncRoutesJob;
    @Autowired
    private DomainReservationSweeper sweeper;
    @Autowired
    private PublishingTeardownService teardownService;
    @Autowired
    private DomainVerifier domainVerifier;
    @Autowired
    private PublishingTest.StubDnsResolver resolver;
    @Autowired
    private RecordingDnsRecordProvider dns;
    @Autowired
    private PlatformDnsRecords platformDnsRecords;
    @Autowired
    private PublishingProperties publishingProperties;
    @Autowired
    private SettingsService settingsService;
    @Autowired
    private DomainRepository domainRepository;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private User owner;
    private String ownerToken;
    private String sysAdminToken;
    private long orgId;
    private long nodeId;
    private long imageId;
    private long workspaceId;
    private String suffix;

    @BeforeEach
    void setUp() throws Exception {
        agent.resetAll();
        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/apply"))
                .willReturn(okApply()));
        dns.reset();
        resolver.clear();
        jdbcTemplate.update("delete from auth_rate_limits where scope in "
                + "('domain_create', 'domain_create_hourly')");

        owner = ensureUser("dns.owner@pusan.ac.kr", "DNS 소유자");
        User sysAdmin = userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow();
        ownerToken = jwtService.createAccessToken(owner);
        sysAdminToken = jwtService.createAccessToken(sysAdmin);
        orgId = SeedFixtures.seedOrgId(jdbcTemplate);
        imageId = jdbcTemplate.queryForObject("select min(id) from os_images", Long.class);
        nodeId = jdbcTemplate.queryForObject("select min(id) from nodes", Long.class);
        suffix = UUID.randomUUID().toString().substring(0, 6);
        workspaceId = createTeam("dns-" + suffix);
    }

    // ── attach / release / revive ────────────────────────────────────────────

    @Test
    void attachEnsuresTheRecordBeforeTheVhostAndExposesItsState() throws Exception {
        long vmId = publishableVm();
        String fqdn = "dns-up-" + suffix + "." + ROOT;
        publish(vmId, "dns-up-" + suffix, 8080).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.domain.dnsStatus").value("PENDING"));
        long domainId = domainIdForVm(vmId);
        long routeId = routeIdForVm(vmId);
        assertThat(dnsStatus(domainId)).isEqualTo("PENDING");

        SEQUENCE.clear();
        routeApplyJob.apply(routeId);

        // The name resolves before the vhost answers, never the other way round.
        assertThat(SEQUENCE).containsExactly("dns:ensure:" + fqdn, "agent:present:" + fqdn);
        assertThat(dns.hasA(fqdn)).isTrue();
        assertThat(dns.a(fqdn).values()).containsExactly(publishingProperties.proxyPublicIp());
        assertThat(dns.a(fqdn).ttlSeconds()).isEqualTo(300);
        assertThat(dnsStatus(domainId)).isEqualTo("APPLIED");
        assertThat(dnsAppliedAt(domainId)).isNotNull();
        assertThat(routeStatus(routeId)).isEqualTo("APPLIED");

        // The state reaches both the user detail and the admin listing.
        mockMvc.perform(get("/api/v1/domains/" + pub("domains", domainId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dnsStatus").value("APPLIED"))
                .andExpect(jsonPath("$.dnsAppliedAt").isNotEmpty())
                .andExpect(jsonPath("$.dnsLastError").value((Object) null));
        Map<UUID, tools.jackson.databind.JsonNode> admin = listAdminDomains();
        assertThat(admin.get(pub("domains", domainId)).get("dnsStatus").asString())
                .isEqualTo("APPLIED");
    }

    @Test
    void releaseRemovesTheRecordAfterTheVhostAndReviveBringsItBack() throws Exception {
        long vmId = publishableVm();
        String fqdn = "dns-rel-" + suffix + "." + ROOT;
        publish(vmId, "dns-rel-" + suffix, 80).andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);
        long routeId = routeIdForVm(vmId);
        routeApplyJob.apply(routeId);
        assertThat(dns.hasA(fqdn)).isTrue();

        SEQUENCE.clear();
        mockMvc.perform(delete("/api/v1/domains/" + pub("domains", domainId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        assertThat(dnsStatus(domainId)).isEqualTo("PENDING");
        routeApplyJob.apply(routeId);

        // The vhost goes first; a name that still resolves to the proxy for a
        // moment only meets its reject page.
        assertThat(SEQUENCE).containsExactly("agent:absent:" + fqdn, "dns:remove:" + fqdn);
        assertThat(dns.hasA(fqdn)).isFalse();
        assertThat(dnsStatus(domainId)).isEqualTo("NONE");
        assertThat(dnsAppliedAt(domainId)).isNull();
        assertThat(routeConfirmed(routeId)).isTrue();
        assertThat(releasedAt(domainId)).isNotNull();

        // The same VM takes the reserved name back: the row revives and the
        // record is owed again.
        publish(vmId, "dns-rel-" + suffix, 81).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.domain.id").value(pub("domains", domainId).toString()))
                .andExpect(jsonPath("$.domain.dnsStatus").value("PENDING"));
        routeApplyJob.apply(routeIdForVm(vmId));
        assertThat(dns.hasA(fqdn)).isTrue();
        assertThat(dnsStatus(domainId)).isEqualTo("APPLIED");
    }

    // ── teardown paths ───────────────────────────────────────────────────────

    @Test
    void vmDeletionTeardownRemovesTheRecordWithTheVhost() throws Exception {
        long vmId = publishableVm();
        String fqdn = "dns-del-" + suffix + "." + ROOT;
        publish(vmId, "dns-del-" + suffix, 80).andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);
        long routeId = routeIdForVm(vmId);
        routeApplyJob.apply(routeId);
        assertThat(dns.hasA(fqdn)).isTrue();

        SEQUENCE.clear();
        teardownService.teardownForVmDeletion(vmId);

        assertThat(SEQUENCE).containsExactly("agent:absent:" + fqdn, "dns:remove:" + fqdn);
        assertThat(domainStatus(domainId)).isEqualTo("REMOVED");
        assertThat(dns.hasA(fqdn)).isFalse();
        assertThat(dnsStatus(domainId)).isEqualTo("NONE");
        assertThat(routeConfirmed(routeId)).isTrue();
    }

    @Test
    void adminForceReleaseRemovesTheRecordWithTheVhost() throws Exception {
        long vmId = publishableVm();
        String fqdn = "dns-force-" + suffix + "." + ROOT;
        publish(vmId, "dns-force-" + suffix, 80).andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);
        long routeId = routeIdForVm(vmId);
        routeApplyJob.apply(routeId);

        mockMvc.perform(post("/api/v1/admin/domains/" + pub("domains", domainId) + "/force-release")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk());
        assertThat(domainStatus(domainId)).isEqualTo("REMOVED");
        assertThat(dnsStatus(domainId)).isEqualTo("PENDING");

        SEQUENCE.clear();
        routeApplyJob.apply(routeId);
        assertThat(SEQUENCE).containsExactly("agent:absent:" + fqdn, "dns:remove:" + fqdn);
        assertThat(dns.hasA(fqdn)).isFalse();
        assertThat(dnsStatus(domainId)).isEqualTo("NONE");
    }

    // ── failure and retry ────────────────────────────────────────────────────

    @Test
    void aFailedRemovalKeepsTheRouteUnconfirmedAndTheSweeperFinishesIt() throws Exception {
        long vmId = publishableVm();
        String fqdn = "dns-swp-" + suffix + "." + ROOT;
        publish(vmId, "dns-swp-" + suffix, 80).andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);
        long routeId = routeIdForVm(vmId);
        routeApplyJob.apply(routeId);

        dns.failWith("zone unreachable");
        mockMvc.perform(delete("/api/v1/domains/" + pub("domains", domainId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        // The vhost is gone, so the push reports the agent's APPLIED (a VM
        // deletion may release its IP); the route stays unconfirmed so the
        // reconciler retries the record, and the rows say why.
        assertThat(routeApplyJob.applyNow(routeId)).isEqualTo(ApplyOutcome.Kind.APPLIED);
        assertThat(routeStatus(routeId)).isEqualTo("REMOVED");
        assertThat(routeConfirmed(routeId)).isFalse();
        assertThat(routeLastError(routeId)).contains("DNS 레코드 삭제 실패").contains("zone unreachable");
        assertThat(dnsStatus(domainId)).isEqualTo("FAILED");
        assertThat(dnsLastError(domainId)).isEqualTo("zone unreachable");
        assertThat(dnsAppliedAt(domainId)).isNotNull();
        assertThat(dns.hasA(fqdn)).isTrue();

        // The grace ends while the provider is still down: the name stays
        // reserved rather than freed with a record still pointing at us.
        jdbcTemplate.update("update domains set released_at = now() - interval '31 days' where id = ?",
                domainId);
        sweeper.sweep();
        assertThat(domainStatus(domainId)).isEqualTo("ACTIVE");
        assertThat(releasedAt(domainId)).isNotNull();
        assertThat(dnsStatus(domainId)).isEqualTo("FAILED");
        assertThat(dns.hasA(fqdn)).isTrue();

        // Provider back: the reclaim takes the record down and frees the name.
        dns.failWith(null);
        sweeper.sweep();
        assertThat(domainStatus(domainId)).isEqualTo("REMOVED");
        assertThat(releasedAt(domainId)).isNull();
        assertThat(dns.hasA(fqdn)).isFalse();
        assertThat(dnsStatus(domainId)).isEqualTo("NONE");
        assertThat(dnsLastError(domainId)).isNull();
    }

    @Test
    void aFailedEnsureHoldsTheRouteAndTheReconcilerRetriesIt() throws Exception {
        settleAllRoutes();
        long vmId = publishableVm();
        String fqdn = "dns-rec-" + suffix + "." + ROOT;
        publish(vmId, "dns-rec-" + suffix, 80).andExpect(status().isAccepted());
        long domainId = domainIdForVm(vmId);
        long routeId = routeIdForVm(vmId);

        dns.failWith("quota exceeded");
        SEQUENCE.clear();
        assertThat(routeApplyJob.applyNow(routeId)).isEqualTo(ApplyOutcome.Kind.FAILED);
        // No vhost was rendered for a name that does not resolve.
        assertThat(SEQUENCE).containsExactly("dns:ensure:" + fqdn);
        assertThat(routeStatus(routeId)).isEqualTo("PENDING");
        assertThat(routeLastError(routeId)).contains("DNS 레코드 생성 실패").contains("quota exceeded");
        assertThat(dnsStatus(domainId)).isEqualTo("FAILED");
        assertThat(dnsLastError(domainId)).isEqualTo("quota exceeded");
        assertThat(dns.hasA(fqdn)).isFalse();

        // Provider back; past the settle grace the reconciler re-pushes the
        // PENDING route, which runs the whole push again.
        dns.failWith(null);
        jdbcTemplate.update("update routes set updated_at = now() - interval '5 minutes' where id = ?",
                routeId);
        SEQUENCE.clear();
        routeReconcileJob.run();
        assertThat(SEQUENCE).containsExactly("dns:ensure:" + fqdn, "agent:present:" + fqdn);
        assertThat(routeStatus(routeId)).isEqualTo("APPLIED");
        assertThat(dnsStatus(domainId)).isEqualTo("APPLIED");
        assertThat(dnsLastError(domainId)).isNull();
        assertThat(dns.hasA(fqdn)).isTrue();
    }

    // ── admin resync ─────────────────────────────────────────────────────────

    @Test
    void resyncAddsMissingRecordsAndPrunesOnlyTrueOrphans() throws Exception {
        String proxy = publishingProperties.proxyPublicIp();
        // A serving platform name from before per-name records: live route,
        // nothing written, nothing in the zone.
        long vmId = publishableVm();
        String legacy = "dns-old-" + suffix + "." + ROOT;
        publish(vmId, "dns-old-" + suffix, 80).andExpect(status().isAccepted());
        long legacyId = domainIdForVm(vmId);
        routeApplyJob.apply(routeIdForVm(vmId));
        jdbcTemplate.update("update domains set dns_status = 'NONE', dns_applied_at = null where id = ?",
                legacyId);
        dns.unseed(legacy, "A");
        // A name held in its reservation grace: its row is live, so a record
        // under it is claimed even though nothing serves.
        String held = "dns-held-" + suffix + "." + ROOT;
        publish(vmId, "dns-held-" + suffix, 80).andExpect(status().isAccepted());
        long heldId = domainIdForVm(vmId);
        long heldRoute = routeIdForVm(vmId);
        routeApplyJob.apply(heldRoute);
        mockMvc.perform(delete("/api/v1/domains/" + pub("domains", heldId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        routeApplyJob.apply(heldRoute);
        assertThat(dns.hasA(held)).isFalse();
        dns.seed(held, "A", List.of(proxy));

        String orphan = "dns-gone-" + suffix + "." + ROOT;
        dns.seed(orphan, "A", List.of(proxy));
        dns.seed("deep.dns-x-" + suffix + "." + ROOT, "A", List.of(proxy));
        dns.seed("dns-elsewhere-" + suffix + "." + ROOT, "A", List.of("198.51.100.7"));
        dns.seed("admin." + ROOT, "A", List.of(proxy)); // a reserved label is never ours
        dns.seed("*." + ROOT, "A", List.of(proxy));
        dns.seed(ROOT, "A", List.of(proxy));
        dns.seed(ROOT, "NS", List.of("ns-cloud-a1.googledomains.com."));
        dns.seed(ROOT, "SOA", List.of("ns-cloud-a1.googledomains.com. dns-admin.google.com. 1 1 1 1 1"));
        dns.seed(ROOT, "CAA", List.of("0 issue \"letsencrypt.org\""));
        dns.seed("_pickle-verify.dns-x-" + suffix + "." + ROOT, "TXT", List.of("\"pv-abc\""));

        agent.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/sync-all"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"applied\":true,\"snapshotGeneration\":"
                                + "{{jsonPath request.body '$.snapshotGeneration'}} }")));
        int callsBefore = dns.calls().size();
        resyncRoutesJob.run();
        List<String> resyncCalls = dns.calls().subList(callsBefore, dns.calls().size());

        assertThat(dns.hasA(legacy)).isTrue();
        assertThat(dnsStatus(legacyId)).isEqualTo("APPLIED");
        assertThat(dns.hasA(orphan)).isFalse();
        assertThat(dns.hasA(held)).isTrue();
        assertThat(dns.hasA("deep.dns-x-" + suffix + "." + ROOT)).isTrue();
        assertThat(dns.hasA("dns-elsewhere-" + suffix + "." + ROOT)).isTrue();
        assertThat(dns.hasA("admin." + ROOT)).isTrue();
        assertThat(dns.hasA("*." + ROOT)).isTrue();
        assertThat(dns.hasA(ROOT)).isTrue();
        assertThat(dns.has(ROOT, "NS")).isTrue();
        assertThat(dns.has(ROOT, "SOA")).isTrue();
        assertThat(dns.has(ROOT, "CAA")).isTrue();
        assertThat(dns.has("_pickle-verify.dns-x-" + suffix + "." + ROOT, "TXT")).isTrue();
        assertThat(resyncCalls).contains("ensure:" + legacy, "remove:" + orphan);
        assertThat(resyncCalls.stream().filter(call -> call.startsWith("remove:")).toList())
                .containsExactly("remove:" + orphan);
    }

    @Test
    void resyncOnlyReportsOrphansWhilePruningIsOff() {
        String orphan = "dns-dry-" + suffix + "." + ROOT;
        dns.seed(orphan, "A", List.of(publishingProperties.proxyPublicIp()));
        PlatformDnsRecords dryRun = new PlatformDnsRecords(dns,
                new DnsProperties("noop", null, null, false, null, null), publishingProperties,
                settingsService, domainRepository, routeRepository, transactionTemplate);

        List<PlatformDnsRecords.ZoneReconciliation> result = dryRun.reconcile(List.of());

        assertThat(result).singleElement().satisfies(zone -> {
            assertThat(zone.rootDomain()).isEqualTo(ROOT);
            assertThat(zone.orphansLeft()).contains(orphan);
            assertThat(zone.pruned()).isEmpty();
        });
        assertThat(dns.hasA(orphan)).isTrue();
        assertThat(dns.calls()).doesNotContain("remove:" + orphan);
    }

    // ── the boundaries ───────────────────────────────────────────────────────

    @Test
    void customDomainsNeverReachTheProvider() throws Exception {
        long vmId = publishableVm();
        String fqdn = "dns-custom-" + suffix + ".example.com";
        mockMvc.perform(post("/api/v1/vms/" + pub("vms", vmId) + "/domains")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"port\":80,\"customDomain\":\"" + fqdn + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.domain.dnsStatus").value("NONE"));
        long domainId = domainIdForVm(vmId);
        long routeId = routeIdForVm(vmId);

        String token = jdbcTemplate.queryForObject(
                "select verification_token from domains where id = ?", String.class, domainId);
        resolver.setTxt("_pickle-verify." + fqdn, List.of(token));
        resolver.setA(fqdn, List.of(publishingProperties.proxyPublicIp()));
        domainVerifier.verifyOne(domainId);
        assertThat(domainStatus(domainId)).isEqualTo("ACTIVE");
        routeApplyJob.apply(routeId);
        assertThat(routeStatus(routeId)).isEqualTo("APPLIED");

        mockMvc.perform(delete("/api/v1/domains/" + pub("domains", domainId))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isAccepted());
        routeApplyJob.apply(routeId);

        assertThat(dns.callsFor(fqdn)).isEmpty();
        assertThat(dnsStatus(domainId)).isEqualTo("NONE");
    }

    @Test
    void anUnconfiguredProviderRefusesPlatformPublishingAndNothingElse() throws Exception {
        long vmId = publishableVm();
        dns.setConfigured(false);
        try {
            publish(vmId, "dns-off-" + suffix, 80)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("VM_INVALID_STATE"));
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from domains where fqdn = ?", Integer.class,
                    "dns-off-" + suffix + "." + ROOT)).isZero();
            // A custom domain is the user's own zone and is not held back.
            mockMvc.perform(post("/api/v1/vms/" + pub("vms", vmId) + "/domains")
                            .header("Authorization", "Bearer " + ownerToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"port\":80,\"customDomain\":\"dns-off-" + suffix
                                    + ".example.com\"}"))
                    .andExpect(status().isAccepted());
        } finally {
            dns.setConfigured(true);
        }
        publish(vmId, "dns-off-" + suffix, 80).andExpect(status().isAccepted());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions publish(long vmId, String subdomain,
            int port) throws Exception {
        return mockMvc.perform(post("/api/v1/vms/" + pub("vms", vmId) + "/domains")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"port\":" + port + ",\"subdomain\":\"" + subdomain + "\"}"));
    }

    private Map<UUID, tools.jackson.databind.JsonNode> listAdminDomains() throws Exception {
        String body = mockMvc.perform(get("/api/v1/admin/domains?size=100")
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<UUID, tools.jackson.databind.JsonNode> byId = new java.util.HashMap<>();
        objectMapper.readTree(body).get("content")
                .forEach(node -> byId.put(UUID.fromString(node.get("id").asString()), node));
        return byId;
    }

    private void settleAllRoutes() {
        jdbcTemplate.update("""
                update routes set applied_generation = generation
                 where applied_generation is null or applied_generation < generation
                """);
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

    private String routeLastError(long routeId) {
        return jdbcTemplate.queryForObject("select last_error from routes where id = ?", String.class,
                routeId);
    }

    private boolean routeConfirmed(long routeId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select applied_generation is not null and applied_generation >= generation "
                        + "from routes where id = ?", Boolean.class, routeId));
    }

    private String domainStatus(long domainId) {
        return jdbcTemplate.queryForObject("select status from domains where id = ?", String.class,
                domainId);
    }

    private String dnsStatus(long domainId) {
        return jdbcTemplate.queryForObject("select dns_status from domains where id = ?", String.class,
                domainId);
    }

    private String dnsLastError(long domainId) {
        return jdbcTemplate.queryForObject("select dns_last_error from domains where id = ?",
                String.class, domainId);
    }

    private Instant dnsAppliedAt(long domainId) {
        return jdbcTemplate.queryForObject("select dns_applied_at from domains where id = ?",
                Instant.class, domainId);
    }

    private Instant releasedAt(long domainId) {
        return jdbcTemplate.queryForObject("select released_at from domains where id = ?",
                Instant.class, domainId);
    }

    /** An approved, running VM with an allocated IP and the owner on its access list. */
    private long publishableVm() {
        long requestId = RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId,
                owner.getId(), "DNS 테스트", imageId, 1, 1024, 10);
        RequestFixtures.approveVmRequest(jdbcTemplate, requestId, owner.getId(), imageId, 1, 1024, 10);
        String hostname = "dns-" + UUID.randomUUID().toString().substring(0, 12);
        long vmId = jdbcTemplate.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values (?, ?, ?, ?, ?, ?, ?, 1, 1024, 10, ?, 'RUNNING'::vm_status)
                returning id
                """, Long.class, nodeId, workspaceId, orgId, requestId, hostname, hostname,
                imageId, VMID_SEQ.incrementAndGet());
        String ip = "172.29.203." + IP_SEQ.getAndIncrement();
        long allocId = jdbcTemplate.queryForObject("""
                insert into ip_allocations (pool_id, ip, vm_id, status)
                values ((select id from ip_pools where name = 'guest-private'), ?::inet, ?, 'ALLOCATED')
                returning id
                """, Long.class, ip, vmId);
        jdbcTemplate.update("update vms set ip_allocation_id = ? where id = ?", allocId, vmId);
        AccessGrantFixtures.grantVmToUser(jdbcTemplate, vmId, owner.getId(), "OWNER");
        return vmId;
    }

    private long createTeam(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/workspaces")
                        .header("Authorization", "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("kind", "TEAM", "name", "DNS 테스트 " + slug))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return SeedFixtures.internalId(jdbcTemplate, "workspaces",
                UUID.fromString(objectMapper.readTree(body).get("id").asString()));
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            user.setRole(UserRole.USER);
            User saved = userRepository.save(user);
            SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), null, UserRole.USER);
            return saved;
        });
    }

    /** 200 with the generation the request carried, which is what the real agent confirms. */
    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okApply() {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"applied\":true,\"generation\":{{jsonPath request.body '$.generation'}} }");
    }

    private UUID pub(String table, long id) {
        return SeedFixtures.publicId(jdbcTemplate, table, id);
    }
}
