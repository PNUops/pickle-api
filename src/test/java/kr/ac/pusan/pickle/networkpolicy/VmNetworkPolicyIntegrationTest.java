package kr.ac.pusan.pickle.networkpolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import kr.ac.pusan.pickle.config.VmFirewallPolicyProperties;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.AccessGrantFixtures;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/** VM-policy durable opt-in, permissions, CAS and audit coverage. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmNetworkPolicyIntegrationTest {

    private static final AtomicInteger VMID = new AtomicInteger(980_000);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("pickle.vm-firewall.enabled", () -> "true");
        registry.add("pickle.vm-firewall.barrier-group", () -> "pickle-guard");
        registry.add("pickle.vm-firewall.ssh-gateway-source-ips", () -> "198.51.100.10");
        registry.add("pickle.vm-firewall.terminal-source-ips", () -> "198.51.100.20");
        registry.add("pickle.vm-firewall.proxy-source-ips", () -> "198.51.100.30");
        registry.add("pickle.vm-firewall.relay-source-ips", () -> "198.51.100.40");
        registry.add("jobrunr.background-job-server.enabled", () -> "false");
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired VmNetworkPolicyStore store;
    @Autowired VmFirewallPolicyProperties properties;
    @Autowired VmNetworkPublishedPathStore publishedPaths;
    @Autowired VmNetworkPathOperationStore pathOperations;
    @Autowired VmNetworkPathCoordinatorJob pathCoordinator;
    @Autowired DataSource dataSource;

    private User editor;
    private User viewer;
    private String editorToken;
    private String viewerToken;
    private long vmId;
    private UUID vmPublicId;

    @BeforeEach
    void setUp() {
        editor = user("vm-policy-editor@pickle.local", "VM 정책 편집자");
        viewer = user("vm-policy-viewer@pickle.local", "VM 정책 열람자");
        editorToken = jwt.createAccessToken(editor);
        viewerToken = jwt.createAccessToken(viewer);
        long orgId = SeedFixtures.seedOrgId(jdbc);
        long workspaceId = jdbc.queryForObject("""
                insert into workspaces (kind, name) values ('PROJECT', ?) returning id
                """, Long.class, "VM 정책 " + UUID.randomUUID());
        long requestId = RequestFixtures.insertVmRequest(jdbc, workspaceId, orgId,
                editor.getId(), "VM 통신 정책 테스트", null, 1, 1024, 10);
        String name = "vm-policy-" + UUID.randomUUID().toString().substring(0, 8);
        vmId = jdbc.queryForObject("""
                insert into vms (node_id, workspace_id, org_id, request_id, name, hostname,
                                 image_id, vcpu, memory_mb, disk_gb, proxmox_vmid, status)
                values ((select min(id) from nodes), ?, ?, ?, ?, ?,
                        (select min(id) from os_images), 1, 1024, 10, ?, 'STOPPED')
                returning id
                """, Long.class, workspaceId, orgId, requestId, name, name, VMID.incrementAndGet());
        vmPublicId = SeedFixtures.publicId(jdbc, "vms", vmId);
        jdbc.update("""
                insert into workspace_members (workspace_id, user_id, role)
                values (?, ?, 'MEMBER'), (?, ?, 'MEMBER')
                """, workspaceId, editor.getId(), workspaceId, viewer.getId());
        AccessGrantFixtures.grantVmToUser(jdbc, vmId, editor.getId(), "EDITOR");
        AccessGrantFixtures.grantVmToUser(jdbc, vmId, viewer.getId(), "VIEWER");
        jdbc.update("""
                update nodes set labels = coalesce(labels, '{}'::jsonb)
                    || '{"vm_firewall_policy":{"schema_version":1},
                         "vm_nic_requirements":{"schema_version":1,
                         "mtu":1370,"firewall":true}}'::jsonb
                 where id = (select node_id from vms where id = ?)
                """, vmId);
        store.initialize(vmId, VmNetworkPolicyHashes.desired(properties, List.of(), List.of()));
    }

    @Test
    void editorReplacesRulesByRevisionWhileViewerIsReadOnly() throws Exception {
        mockMvc.perform(get("/api/v1/vms/" + vmPublicId + "/network-policy")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.applyState").value("PENDING"))
                .andExpect(jsonPath("$.systemRules.length()").value(4));
        update(viewerToken, 0).andExpect(status().isForbidden());
        update(editorToken, 0).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.rules[0].peer").value("203.0.113.0/24"))
                .andExpect(jsonPath("$.applyState").value("PENDING"));
        update(editorToken, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_NETWORK_POLICY_REVISION_CONFLICT"));

        assertThat(jdbc.queryForObject("""
                select (detail ->> 'adminIntervention')::boolean from audit_logs
                 where action = 'vm.network_policy_update' and target_id = ?
                 order by id desc limit 1
                """, Boolean.class, vmPublicId.toString())).isFalse();
    }

    @Test
    void rowlessVmCannotBeAdoptedThroughPublicPut() throws Exception {
        jdbc.update("delete from vm_network_policies where vm_id = ?", vmId);
        update(editorToken, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_NETWORK_POLICY_UNAVAILABLE"));
    }

    @Test
    void publicPutRejectsCreatingAndInFlightPowerOrDeviceWork() throws Exception {
        jdbc.update("update vms set status = 'CREATING' where id = ?", vmId);
        update(editorToken, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_NETWORK_POLICY_UNAVAILABLE"));

        jdbc.update("update vms set status = 'STOPPED', pending_power_action = 'START',"
                + " pending_power_action_at = now() where id = ?", vmId);
        update(editorToken, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("작성 중인 규칙은 그대로")));

        jdbc.update("update vms set pending_power_action = null, pending_power_action_at = null,"
                + " power_operation_id = ? where id = ?", UUID.randomUUID(), vmId);
        update(editorToken, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VM_NETWORK_POLICY_UNAVAILABLE"));
    }

    @Test
    void appliedResultCannotPublishAfterTargetTupleChanges() {
        long poolId = jdbc.queryForObject("""
                select n.ip_pool_id from nodes n join vms v on v.node_id = n.id where v.id = ?
                """, Long.class, vmId);
        long allocationId = jdbc.queryForObject("""
                insert into ip_allocations (pool_id, ip, vm_id, status, allocated_at)
                values (?, ?::inet, ?, 'ALLOCATED', now()) returning id
                """, Long.class, poolId, "192.0.2." + (vmId % 200 + 20), vmId);
        jdbc.update("update vms set ip_allocation_id = ? where id = ?", allocationId, vmId);
        var target = store.target(vmId, 1370);
        var policy = store.find(vmId).orElseThrow();

        jdbc.update("update vms set proxmox_vmid = proxmox_vmid + 1000000 where id = ?", vmId);

        assertThat(store.markApplied(vmId, policy.desiredGeneration(),
                policy.desiredHash(), target)).isFalse();
        assertThat(store.markFailedClosed(vmId, policy.desiredGeneration(),
                policy.desiredHash(), target, "guarded")).isFalse();
        assertThat(store.find(vmId).orElseThrow().state())
                .isEqualTo(VmNetworkPolicyApplyState.PENDING);
    }

    @Test
    void managedDerivedPathsFollowDurableOperationsInsteadOfConsumerRows() {
        String emptyHash = VmNetworkPolicyHashes.desired(
                properties, publishedPaths.find(vmId), List.of());
        long domain = jdbc.queryForObject("""
                insert into domains (workspace_id, org_id, vm_id, kind, fqdn, root_domain, status)
                select workspace_id, org_id, id, 'PLATFORM', ?, 'pusan.dev', 'ACTIVE'
                  from vms where id = ? returning id
                """, Long.class, "vm-policy-path-" + vmId + ".pusan.dev", vmId);
        jdbc.update("""
                insert into routes (domain_id, target_port, status, generation)
                values (?, 8443, 'PENDING', nextval('route_generation_seq'))
                """, domain);
        jdbc.update("""
                insert into port_mappings
                    (relay_id, vm_id, proto, public_port, target_port, status,
                     last_change_generation, created_by)
                values ((select min(id) from relays), ?, 'UDP', ?, 9443, 'ACTIVE', 1, ?)
                """, vmId, 30_000 + VMID.get() % 20_000, editor.getId());

        assertThat(publishedPaths.find(vmId)).isEmpty();
        long routeId = jdbc.queryForObject(
                "select id from routes where domain_id = ?", Long.class, domain);
        long mappingId = jdbc.queryForObject(
                "select id from port_mappings where vm_id = ?", Long.class, vmId);
        jdbc.update("""
                insert into vm_network_derived_paths
                    (vm_id, owner_kind, owner_id, source_kind, protocol, target_port)
                values (?, 'HTTP_ROUTE', ?, 'PROXY', 'TCP', 8443),
                       (?, 'PORT_MAPPING', ?, 'RELAY', 'UDP', 9443)
                """, vmId, routeId, vmId, mappingId);
        var activePaths = publishedPaths.find(vmId);
        assertThat(activePaths).containsExactly(
                new VmNetworkPolicyCompiler.PublishedPath(
                        "198.51.100.30", VmNetworkRule.Protocol.TCP, 8443),
                new VmNetworkPolicyCompiler.PublishedPath(
                        "198.51.100.40", VmNetworkRule.Protocol.UDP, 9443));
        assertThat(VmNetworkPolicyHashes.desired(properties, activePaths, List.of()))
                .isNotEqualTo(emptyHash);

        jdbc.update("update routes set status = 'REMOVED' where domain_id = ?", domain);
        jdbc.update("update port_mappings set status = 'SUSPENDED' where vm_id = ?", vmId);
        assertThat(publishedPaths.find(vmId)).containsExactlyElementsOf(activePaths);
        jdbc.update("delete from vm_network_derived_paths where vm_id = ?", vmId);
        assertThat(publishedPaths.find(vmId)).isEmpty();
    }

    @Test
    void staleHttpWorkersCannotFinishASupersededCloseOrLeakAnOrphanPath() {
        long domain = jdbc.queryForObject("""
                insert into domains (workspace_id, org_id, vm_id, kind, fqdn, root_domain, status)
                select workspace_id, org_id, id, 'PLATFORM', ?, 'pusan.dev', 'ACTIVE'
                  from vms where id = ? returning id
                """, Long.class, "vm-policy-race-" + vmId + ".pusan.dev", vmId);
        long route = jdbc.queryForObject("""
                insert into routes (domain_id, target_port, status, generation)
                values (?, 8080, 'PENDING', nextval('route_generation_seq')) returning id
                """, Long.class, domain);
        assertThat(pathOperations.openHttp(vmId, route, 8080)).isTrue();
        var staleOpen = livePathOperation(route);

        assertThatThrownBy(() -> pathOperations.openHttp(vmId, route, 8081))
                .isInstanceOf(kr.ac.pusan.pickle.common.error.ApiException.class);
        assertThat(pathCount(route)).isEqualTo(1);
        assertThat(pathOperations.closeHttp(vmId, route)).isTrue();
        assertThat(pathOperations.finishHttpConsumer(staleOpen,
                VmNetworkPathOperationStore.Phase.DONE, 1)).isFalse();
        assertThat(pathCount(route)).isEqualTo(1);
        assertThat(livePathOperation(route).action())
                .isEqualTo(VmNetworkPathOperationStore.Action.CLOSE);

        jdbc.update("delete from vm_network_path_operations where owner_kind = 'HTTP_ROUTE' and owner_id = ?",
                route);
        jdbc.update("delete from vm_network_derived_paths where owner_kind = 'HTTP_ROUTE' and owner_id = ?",
                route);
        pathOperations.openHttp(vmId, route, 8080);
        jdbc.update("""
                update vm_network_path_operations set phase = 'DONE'
                 where owner_kind = 'HTTP_ROUTE' and owner_id = ?
                """, route);
        assertThat(pathOperations.replaceHttp(vmId, route, 8080, 8081)).isTrue();
        var staleReplace = livePathOperation(route);
        assertThat(pathCount(route)).isEqualTo(2);

        assertThat(pathOperations.closeHttp(vmId, route)).isTrue();
        assertThat(pathOperations.finishHttpConsumer(staleReplace,
                VmNetworkPathOperationStore.Phase.POLICY_REMOVE, 2)).isFalse();
        assertThat(pathCount(route)).isEqualTo(2);
        assertThat(livePathOperation(route).action())
                .isEqualTo(VmNetworkPathOperationStore.Action.CLOSE);
    }

    @Test
    void portActivationAndOperationPhaseRollbackAtTheCrashBoundary() {
        long relay = jdbc.queryForObject("select min(id) from relays", Long.class);
        long before = jdbc.queryForObject(
                "select mapping_generation from relays where id = ?", Long.class, relay);
        long mapping = jdbc.queryForObject("""
                insert into port_mappings
                    (relay_id, vm_id, proto, public_port, target_port, status,
                     delivery_state, flow_mark, consumer_mapping_id,
                     last_change_generation, created_by)
                values (?, ?, 'TCP', ?, 8080, 'PENDING', 'PENDING', 101, ?, ?, ?)
                returning id
                """, Long.class, relay, vmId, 31_000 + (int) (vmId % 10_000),
                1_000_000L + vmId, before, editor.getId());
        pathOperations.openPort(vmId, mapping, "TCP", 8080,
                VmNetworkPathOperationStore.Action.OPEN);
        UUID operationId = jdbc.queryForObject("""
                select id from vm_network_path_operations
                 where owner_kind = 'PORT_MAPPING' and owner_id = ? and phase <> 'DONE'
                """, UUID.class, mapping);
        var operation = pathOperations.find(operationId).orElseThrow();

        assertThatThrownBy(() -> pathCoordinator.activatePortConsumerAndMove(
                operation, 2, () -> { throw new IllegalStateException("simulated crash"); }))
                .hasMessage("simulated crash");
        assertThat(jdbc.queryForObject(
                "select delivery_state from port_mappings where id = ?", String.class, mapping))
                .isEqualTo("PENDING");
        assertThat(pathOperations.find(operationId).orElseThrow().phase())
                .isEqualTo(VmNetworkPathOperationStore.Phase.POLICY_ADD);
        assertThat(jdbc.queryForObject(
                "select mapping_generation from relays where id = ?", Long.class, relay))
                .isEqualTo(before);

        pathCoordinator.activatePortConsumerAndMove(operation, 2, () -> { });
        assertThat(jdbc.queryForObject(
                "select delivery_state from port_mappings where id = ?", String.class, mapping))
                .isEqualTo("ACTIVE");
        var advanced = pathOperations.find(operationId).orElseThrow();
        assertThat(advanced.phase()).isEqualTo(VmNetworkPathOperationStore.Phase.CONSUMER_APPLY);
        assertThat(advanced.consumerGeneration()).isGreaterThan(before);
    }

    @Test
    void pendingRetirementSerializesAgainstActivationAndRevalidatesTheObservedState()
            throws Exception {
        long relay = jdbc.queryForObject("select min(id) from relays", Long.class);
        long mapping = insertPendingManagedMapping(relay, 8082, 102, 1_100_000L + vmId);
        pathOperations.openPort(vmId, mapping, "TCP", 8082,
                VmNetworkPathOperationStore.Action.OPEN);
        var observedOpen = livePortOperation(mapping);
        CountDownLatch observed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var retirement = executor.submit(() -> pathOperations.retirePort(vmId, mapping,
                    VmNetworkPathOperationStore.Action.DELETE, () -> {
                        observed.countDown();
                        try {
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test barrier timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }));
            assertThat(observed.await(10, TimeUnit.SECONDS)).isTrue();
            var activation = executor.submit(() ->
                    pathCoordinator.activatePortConsumerAndMove(observedOpen, 2, () -> { }));
            release.countDown();
            assertThat(retirement.get(10, TimeUnit.SECONDS)).isTrue();
            activation.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject(
                "select delivery_state from port_mappings where id = ?", String.class, mapping))
                .isEqualTo("PENDING");
        var current = livePortOperation(mapping);
        assertThat(current.phase()).isEqualTo(VmNetworkPathOperationStore.Phase.POLICY_REMOVE);
        assertThat(current.action()).isEqualTo(VmNetworkPathOperationStore.Action.DELETE);
        assertThat(jdbc.queryForObject("""
                select count(*) from relay_mapping_retirements where mapping_row_id = ?
                """, Long.class, mapping)).isZero();
    }

    @Test
    void staleConsumerFromAnOldEpochCannotActivateAResumedMapping() {
        long relay = jdbc.queryForObject("select min(id) from relays", Long.class);
        long mapping = insertPendingManagedMapping(relay, 8083, 103, 1_200_000L + vmId);
        pathOperations.openPort(vmId, mapping, "TCP", 8083,
                VmNetworkPathOperationStore.Action.OPEN);
        var oldEpoch = livePortOperation(mapping);
        assertThat(pathOperations.move(oldEpoch, VmNetworkPathOperationStore.Phase.DONE,
                2L, 3L, null)).isTrue();
        jdbc.update("""
                update port_mappings set status = 'SUSPENDED', delivery_state = 'SUSPENDED',
                       flow_mark = 203, consumer_mapping_id = ? where id = ?
                """, 1_300_000L + vmId, mapping);
        jdbc.update("""
                delete from vm_network_derived_paths
                 where owner_kind = 'PORT_MAPPING' and owner_id = ?
                """, mapping);
        pathOperations.openPort(vmId, mapping, "TCP", 8083,
                VmNetworkPathOperationStore.Action.RESUME);
        var resumed = livePortOperation(mapping);
        jdbc.update("""
                update port_mappings set status = 'PENDING', delivery_state = 'PENDING'
                 where id = ?
                """, mapping);
        pathCoordinator.activatePortConsumerAndMove(resumed, 4, () -> { });
        var current = pathOperations.find(resumed.id()).orElseThrow();

        assertThat(pathCoordinator.finishPortConsumer(oldEpoch)).isFalse();
        assertThat(jdbc.queryForObject(
                "select status from port_mappings where id = ?", String.class, mapping))
                .isEqualTo("PENDING");
        assertThat(pathCoordinator.finishPortConsumer(current)).isTrue();
        assertThat(jdbc.queryForObject(
                "select status from port_mappings where id = ?", String.class, mapping))
                .isEqualTo("ACTIVE");
    }

    @Test
    void concurrentRevisionHasExactlyOneWinner() throws Exception {
        var firstRule = new VmNetworkRule(VmNetworkRule.Direction.IN,
                VmNetworkRule.Action.ACCEPT, VmNetworkRule.Protocol.TCP,
                CidrBlock.parse("192.0.2.0/24"), 443, 443);
        var secondRule = new VmNetworkRule(VmNetworkRule.Direction.IN,
                VmNetworkRule.Action.DROP, VmNetworkRule.Protocol.ANY,
                CidrBlock.parse("198.51.100.0/24"), null, null);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> store.replace(vmId, 0, List.of(firstRule),
                    VmNetworkPolicyHashes.desired(properties, List.of(), List.of(firstRule)),
                    editor.getId()));
            var second = pool.submit(() -> store.replace(vmId, 0, List.of(secondRule),
                    VmNetworkPolicyHashes.desired(properties, List.of(), List.of(secondRule)),
                    editor.getId()));
            int successes = 0;
            int conflicts = 0;
            for (var future : List.of(first, second)) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                    successes++;
                } catch (java.util.concurrent.ExecutionException failure) {
                    if (failure.getCause() instanceof kr.ac.pusan.pickle.common.error.ApiException) {
                        conflicts++;
                    } else {
                        throw failure;
                    }
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void oneStatementSnapshotCannotMixOldRulesWithNewRevision() throws Exception {
        var oldRules = List.of(
                new VmNetworkRule(VmNetworkRule.Direction.IN, VmNetworkRule.Action.ACCEPT,
                        VmNetworkRule.Protocol.TCP, CidrBlock.parse("192.0.2.0/24"), 80, 80),
                new VmNetworkRule(VmNetworkRule.Direction.OUT, VmNetworkRule.Action.DROP,
                        VmNetworkRule.Protocol.ANY, CidrBlock.parse("198.51.100.0/24"), null, null));
        String oldHash = VmNetworkPolicyHashes.desired(properties, List.of(), oldRules);
        store.replace(vmId, 0, oldRules, oldHash, editor.getId());

        try (Connection reader = dataSource.getConnection()) {
            reader.setAutoCommit(false);
            reader.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try (PreparedStatement statement = reader.prepareStatement(
                    VmNetworkPolicyStore.FIND_SQL)) {
                statement.setFetchSize(1);
                statement.setLong(1, vmId);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getLong("revision")).isEqualTo(1);
                    LinkedHashSet<String> peers = new LinkedHashSet<>();
                    peers.add(rows.getString("peer"));

                    var replacement = new VmNetworkRule(VmNetworkRule.Direction.IN,
                            VmNetworkRule.Action.ACCEPT, VmNetworkRule.Protocol.TCP,
                            CidrBlock.parse("203.0.113.0/24"), 443, 443);
                    store.replace(vmId, 1, List.of(replacement),
                            VmNetworkPolicyHashes.desired(
                                    properties, List.of(), List.of(replacement)), editor.getId());

                    while (rows.next()) {
                        assertThat(rows.getLong("revision")).isEqualTo(1);
                        assertThat(rows.getString("desired_hash")).isEqualTo(oldHash);
                        peers.add(rows.getString("peer"));
                    }
                    assertThat(peers).containsExactly("192.0.2.0/24", "198.51.100.0/24");
                }
            } finally {
                reader.rollback();
            }
        }
        assertThat(store.find(vmId).orElseThrow().revision()).isEqualTo(2);
        assertThat(store.find(vmId).orElseThrow().rules()).singleElement()
                .satisfies(rule -> assertThat(rule.peer().toString()).isEqualTo("203.0.113.0/24"));
    }

    @Test
    void schemaRejectsPartialPortsAndAppliedStateWithoutProof() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into vm_network_policy_rules
                    (vm_id, position, direction, action, protocol, peer, port_start, port_end)
                values (?, 0, 'IN', 'ACCEPT', 'TCP', '192.0.2.0/24'::cidr, 443, null)
                """, vmId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                update vm_network_policies
                   set apply_state = 'APPLIED', applied_generation = null, applied_hash = null
                 where vm_id = ?
                """, vmId)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    private org.springframework.test.web.servlet.ResultActions update(String token, long revision)
            throws Exception {
        return mockMvc.perform(put("/api/v1/vms/" + vmPublicId + "/network-policy")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "expectedRevision", revision,
                        "rules", List.of(Map.of(
                                "direction", "IN", "action", "ACCEPT", "protocol", "TCP",
                                "peer", "203.0.113.0/24", "portStart", 443, "portEnd", 443))))));
    }

    private VmNetworkPathOperationStore.Operation livePathOperation(long routeId) {
        UUID id = jdbc.queryForObject("""
                select id from vm_network_path_operations
                 where owner_kind = 'HTTP_ROUTE' and owner_id = ? and phase <> 'DONE'
                """, UUID.class, routeId);
        return pathOperations.find(id).orElseThrow();
    }

    private VmNetworkPathOperationStore.Operation livePortOperation(long mappingId) {
        UUID id = jdbc.queryForObject("""
                select id from vm_network_path_operations
                 where owner_kind = 'PORT_MAPPING' and owner_id = ? and phase <> 'DONE'
                """, UUID.class, mappingId);
        return pathOperations.find(id).orElseThrow();
    }

    private long insertPendingManagedMapping(long relayId, int targetPort,
            long flowMark, long consumerMappingId) {
        long generation = jdbc.queryForObject(
                "select mapping_generation from relays where id = ?", Long.class, relayId);
        return jdbc.queryForObject("""
                insert into port_mappings
                    (relay_id, vm_id, proto, public_port, target_port, status,
                     delivery_state, flow_mark, consumer_mapping_id,
                     last_change_generation, created_by)
                values (?, ?, 'TCP', ?, ?, 'PENDING', 'PENDING', ?, ?, ?, ?)
                returning id
                """, Long.class, relayId, vmId, 32_000 + targetPort, targetPort,
                flowMark, consumerMappingId, generation, editor.getId());
    }

    private long pathCount(long routeId) {
        return jdbc.queryForObject("""
                select count(*) from vm_network_derived_paths
                 where owner_kind = 'HTTP_ROUTE' and owner_id = ?
                """, Long.class, routeId);
    }

    private User user(String email, String name) {
        User user = users.findByEmail(email).orElseGet(() -> new User(email, "{test}", name));
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        user.setRole(UserRole.USER);
        User saved = users.saveAndFlush(user);
        jdbc.update("delete from user_org_roles where user_id = ?", saved.getId());
        return saved;
    }
}
