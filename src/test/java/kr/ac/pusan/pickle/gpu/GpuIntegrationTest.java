package kr.ac.pusan.pickle.gpu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.proxmox.ProxmoxClient;
import kr.ac.pusan.pickle.proxmox.dto.TaskStatus;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.settings.SettingsService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.VmRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
@Transactional
class GpuIntegrationTest {
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired GpuStore store;
    @Autowired GpuAllocationScheduler scheduler;
    @Autowired GpuMutationService mutations;
    @Autowired GpuOperationJob operations;
    @Autowired kr.ac.pusan.pickle.provisioning.DeleteVmJob deleteVmJob;
    @Autowired GpuReconciliationService reconciliation;
    @Autowired GpuQueryService query;
    @Autowired GpuLowUtilizationJob lowUtil;
    @Autowired GpuReviewService reviews;
    @Autowired SettingsService settings;
    @Autowired UserRepository users;
    @Autowired VmRepository vms;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired Clock clock;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    @Autowired kr.ac.pusan.pickle.provisioning.VmPowerOperationGuard powerGuard;
    @MockitoBean ProxmoxClient proxmox;
    @MockitoBean GpuGuestReadiness guest;
    private long workspace;
    private long org;
    private long node;
    private AuthenticatedUser owner;
    private AuthenticatedUser admin;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<Map<String, Object>> config = new AtomicReference<>(Map.of());
    private final AtomicReference<List<Map<String, Object>>> pending = new AtomicReference<>(List.of());

    @BeforeEach
    void setup() {
        org = SeedFixtures.seedOrgId(jdbc);
        node = jdbc.queryForObject("select min(id) from nodes", Long.class);
        workspace = jdbc.queryForObject("insert into workspaces(kind,name) values ('TEAM','GPU test') returning id", Long.class);
        User user = new User(UUID.randomUUID() + "@example.com", "unused", "GPU owner");
        user.setStatus(UserStatus.ACTIVE);
        user = users.saveAndFlush(user);
        owner = new AuthenticatedUser(user.getId(), user.getPublicId(), user.getEmail(), UserRole.USER, Map.of());
        User adminUser = users.findById(SeedFixtures.sysadminId(jdbc)).orElseThrow();
        admin = new AuthenticatedUser(adminUser.getId(), adminUser.getPublicId(), adminUser.getEmail(), UserRole.SYS_ADMIN, Map.of());
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values (?,?,'OWNER')", workspace, owner.id());
        running.set(false); config.set(Map.of()); pending.set(List.of());
        when(guest.blockingReason(any())).thenReturn(null);
        when(proxmox.currentVmStatus(anyString(), anyString(), anyInt())).thenAnswer(i -> Map.of("status", running.get() ? "running" : "stopped"));
        when(proxmox.currentVmConfig(anyString(), anyString(), anyInt())).thenAnswer(i -> config.get());
        when(proxmox.pendingVmConfig(anyString(), anyString(), anyInt())).thenAnswer(i -> pending.get());
        when(proxmox.shutdown(anyString(), anyString(), anyInt(), anyInt())).thenAnswer(i -> { running.set(false); return "shutdown-task"; });
        when(proxmox.start(anyString(), anyString(), anyInt())).thenAnswer(i -> { running.set(true); return "start-task"; });
        when(proxmox.taskStatus(anyString(), anyString(), anyString())).thenReturn(new TaskStatus("stopped", "OK", "task"));
        doAnswer(i -> {
            Map<String, String> params = i.getArgument(3);
            if (params.containsKey("hostpci0")) { config.set(Map.of("hostpci0", params.get("hostpci0"))); }
            if (params.containsKey("delete")) { config.set(Map.of()); }
            return null;
        }).when(proxmox).config(anyString(), anyString(), anyInt(), anyMap());
    }

    @Test
    void theFirstHolderWithoutAVmOwnsTheCardAndStartsTheLease() {
        long card = gpu(); long first = allocation(48); long second = allocation(48);
        scheduler.allocate();
        assertThat(store.allocation(first).orElseThrow().status()).isEqualTo(GpuAllocationStatus.ALLOCATED);
        assertThat(store.allocation(first).orElseThrow().gpuId()).isEqualTo(card);
        assertThat(store.allocation(first).orElseThrow().vmId()).isNull();
        assertThat(store.allocation(first).orElseThrow().allocatedAt()).isNotNull();
        assertThat(store.allocation(second).orElseThrow().status()).isEqualTo(GpuAllocationStatus.QUEUED);
        assertThat(jdbc.queryForObject("select count(*) from notifications where dedup_key = ?", Long.class, "gpu.allocated:" + first)).isEqualTo(1);
        scheduler.allocate();
        assertThat(jdbc.queryForObject("select count(*) from notifications where dedup_key = ?", Long.class, "gpu.allocated:" + first)).isEqualTo(1);
    }

    @Test
    void aSecondCardCanServeTheNextHolder() {
        gpu(); gpu(); long first = allocation(48); long second = allocation(48);
        scheduler.allocate();
        assertThat(store.allocation(first).orElseThrow().gpuId()).isNotEqualTo(store.allocation(second).orElseThrow().gpuId());
    }

    @Test
    void priorityIsAnAuditedSystemOperatorDecision() {
        gpu(); long first = allocation(48); long second = allocation(48);
        assertThatThrownBy(() -> mutations.priority(owner, publicId(second), 10, "course", null)).isInstanceOf(ApiException.class);
        mutations.priority(admin, publicId(second), 10, "course deadline", null);
        scheduler.allocate();
        assertThat(store.allocation(second).orElseThrow().status()).isEqualTo(GpuAllocationStatus.ALLOCATED);
        assertThat(store.allocation(first).orElseThrow().status()).isEqualTo(GpuAllocationStatus.QUEUED);
    }

    @Test
    void attachingAndDetachingPreserveTheLeaseAndOriginalPowerState() {
        long id = allocated(); long vm = vm();
        Instant end = store.allocation(id).orElseThrow().leaseEndsAt();
        mutations.attach(owner, publicId(id), vmPublicId(vm), null);
        run(id);
        assertThat(store.allocation(id).orElseThrow().connectionStatus()).isEqualTo(GpuConnectionStatus.ATTACHED);
        verify(proxmox, never()).start(anyString(), anyString(), anyInt());
        mutations.detach(owner, publicId(id), null);
        run(id);
        var detached = store.allocation(id).orElseThrow();
        assertThat(detached.connectionStatus()).isEqualTo(GpuConnectionStatus.NONE);
        assertThat(detached.status()).isEqualTo(GpuAllocationStatus.ALLOCATED);
        assertThat(detached.leaseEndsAt()).isEqualTo(end);
        assertThat(detached.unattachedSince()).isNotNull();
    }

    @Test
    void runningVmIsRestartedOnlyAfterExplicitAttachment() {
        long id = allocated(); long vm = vm();
        jdbc.update("update vms set status = 'RUNNING' where id = ?", vm); running.set(true);
        mutations.attach(owner, publicId(id), vmPublicId(vm), null); run(id);
        verify(proxmox).shutdown(anyString(), anyString(), anyInt(), anyInt());
        verify(proxmox).start(anyString(), anyString(), anyInt());
        assertThat(store.allocation(id).orElseThrow().connectionStatus()).isEqualTo(GpuConnectionStatus.ATTACHED);
    }

    @Test
    void missingGuestReadinessBlocksBeforeAnyVmMutation() {
        long id = allocated(); long vm = vm(); when(guest.blockingReason(any())).thenReturn("not prepared");
        assertThatThrownBy(() -> mutations.attach(owner, publicId(id), vmPublicId(vm), null)).isInstanceOf(ApiException.class);
        assertThat(store.allocation(id).orElseThrow().connectionStatus()).isEqualTo(GpuConnectionStatus.NONE);
        assertThat(jdbc.queryForObject("select pending_power_action from vms where id = ?", String.class, vm)).isNull();
        verify(proxmox, never()).config(anyString(), anyString(), anyInt(), anyMap());
    }

    @Test
    void pendingOnlyConfigurationNeverCompletesOrFreesTheCard() {
        long id = allocated(); long vm = vm();
        doAnswer(i -> { pending.set(List.of(Map.of("key", "hostpci0", "pending", "mapping=test"))); return null; })
                .when(proxmox).config(anyString(), anyString(), anyInt(), anyMap());
        mutations.attach(owner, publicId(id), vmPublicId(vm), null); run(id);
        var a = store.allocation(id).orElseThrow();
        assertThat(a.connectionStatus()).isEqualTo(GpuConnectionStatus.ERROR);
        assertThat(a.status()).isEqualTo(GpuAllocationStatus.ALLOCATED);
        assertThat(store.available(a.gpuId())).isFalse();
        assertThatThrownBy(() -> reconciliation.reconcile(admin, a.publicId(), "inspect", null)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select power_operation_id from vms where id = ?", UUID.class, vm)).isEqualTo(a.operationId());
    }

    @Test
    void remoteTaskStillRunningPreventsReconciliationAfterTimeout() {
        long id = allocated(); long vm = vm(); jdbc.update("update vms set status='RUNNING' where id=?", vm); running.set(true);
        doThrow(new IllegalStateException("timeout")).when(proxmox).awaitTask(anyString(), anyString(), anyString(), any(Duration.class));
        when(proxmox.taskStatus(anyString(), anyString(), anyString())).thenReturn(new TaskStatus("running", null, "shutdown-task"));
        mutations.attach(owner, publicId(id), vmPublicId(vm), null); run(id);
        assertThatThrownBy(() -> reconciliation.reconcile(admin, publicId(id), "inspect", null)).isInstanceOf(ApiException.class);
        assertThat(store.allocation(id).orElseThrow().operationId()).isNotNull();
    }

    @Test
    void ordinaryStalePowerSweepCannotClearAGpuOperation() {
        long id = allocated(); long vm = vm(); mutations.attach(owner, publicId(id), vmPublicId(vm), null);
        jdbc.update("update vms set pending_power_action_at = now() - interval '2 hours' where id=?", vm);
        vms.clearStalePowerActionClaims(clock.instant().minus(Duration.ofMinutes(10)), clock.instant());
        assertThat(jdbc.queryForObject("select power_operation_id from vms where id=?", UUID.class, vm)).isNotNull();
        assertThat(vms.clearPowerActionClaim(vm, clock.instant())).isZero();
    }

    @Test
    void vmDeletionDetachesWithoutRestartAndKeepsTheAllocation() {
        long id = allocated(); long vm = vm(); mutations.attach(owner, publicId(id), vmPublicId(vm), null); run(id);
        jdbc.update("update vms set status='DELETING',delete_kind='FORCE',delete_scheduled_for=now() where id=?", vm);
        running.set(true); entityManager.clear();
        assertThat(mutations.prepareVmDeletion(vm)).isFalse(); run(id);
        assertThat(mutations.prepareVmDeletion(vm)).isTrue();
        assertThat(store.allocation(id).orElseThrow().status()).isEqualTo(GpuAllocationStatus.ALLOCATED);
        assertThat(store.allocation(id).orElseThrow().connectionStatus()).isEqualTo(GpuConnectionStatus.NONE);
        verify(proxmox, never()).start(anyString(), anyString(), anyInt());
    }

    @Test
    void currentSettingsChangeReviewsButNeverExistingLeaseDeadlines() {
        long id = allocated(); Instant end = store.allocation(id).orElseThrow().leaseEndsAt();
        jdbc.update("update gpu_allocations set unattached_since=now()-interval '13 hours' where id=?", id);
        settings.update(admin, SettingsService.GPU_UNATTACHED_REVIEW_HOURS, json.readTree("14"), null);
        lowUtil.check(); assertThat(reviewCount(id)).isZero();
        settings.update(admin, SettingsService.GPU_UNATTACHED_REVIEW_HOURS, json.readTree("12"), null);
        lowUtil.check(); lowUtil.check(); assertThat(reviewCount(id)).isEqualTo(1);
        assertThat(store.allocation(id).orElseThrow().leaseEndsAt()).isEqualTo(end);
        assertThat(store.allocation(id).orElseThrow().status()).isEqualTo(GpuAllocationStatus.ALLOCATED);
    }

    @Test
    void missingReviewSettingDoesNotFallBackToTwelveHours() {
        long id = allocated(); jdbc.update("update gpu_allocations set unattached_since=now()-interval '24 hours' where id=?", id);
        jdbc.update("delete from settings where key=?", SettingsService.GPU_UNATTACHED_REVIEW_HOURS);
        lowUtil.check(); assertThat(reviewCount(id)).isZero();
        assertThat(store.allocation(id).orElseThrow().status()).isEqualTo(GpuAllocationStatus.ALLOCATED);
    }

    @Test
    void missingUtilizationSamplesProduceNoLowUseReview() {
        long id = allocated(); long vm = vm(); mutations.attach(owner, publicId(id), vmPublicId(vm), null); run(id);
        jdbc.update("update gpu_allocations set attached_at=now()-interval '24 hours' where id=?", id);
        lowUtil.check(); assertThat(reviewCount(id)).isZero();
    }

    @Test
    void waitingHolderBlocksUserExtensionButNotAnAuditedAdminException() {
        long id = allocated(); allocation(48);
        assertThatThrownBy(() -> mutations.extend(owner, publicId(id), 1, null, null, false)).isInstanceOf(ApiException.class);
        assertThat(mutations.extend(admin, publicId(id), 1, "deadline", null, true).grantedLeaseHours()).isEqualTo(49);
    }

    @Test
    void outsiderGetsMaskedNotFoundAndWorkspaceMemberGetsRestrictedList() throws Exception {
        long id = allocated();
        AuthenticatedUser outsider = new AuthenticatedUser(admin.id(), admin.publicId(), admin.email(), UserRole.USER, Map.of());
        mvc.perform(get("/api/v1/gpu-allocations/" + publicId(id)).with(authentication(auth(outsider)))).andExpect(status().isNotFound());
        jdbc.update("delete from resource_access_grants where resource_type='GPU' and resource_id=?", id);
        var row = query.list(owner, null, 0, 20).content().stream().filter(a -> a.id().equals(publicId(id))).findFirst().orElseThrow();
        assertThat(row.accessLimited()).isTrue(); assertThat(row.gpu()).isNull(); assertThat(row.leaseEndsAt()).isNull();
        mvc.perform(get("/api/v1/gpu-allocations/" + publicId(id)).with(authentication(auth(owner)))).andExpect(status().isForbidden());
    }

    @Test
    void explicitConfirmationAndPositiveDurationAreRequired() throws Exception {
        long id = allocated(); long vm = vm();
        mvc.perform(post("/api/v1/gpu-allocations/"+publicId(id)+"/attach").with(authentication(auth(owner)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"vmId\":\""+vmPublicId(vm)+"\",\"confirmed\":false}")).andExpect(status().isUnprocessableContent());
        UUID ws = SeedFixtures.publicId(jdbc,"workspaces",workspace);
        UUID orgId = SeedFixtures.publicId(jdbc,"orgs",org);
        mvc.perform(post("/api/v1/requests").with(authentication(auth(owner))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"GPU\",\"workspaceId\":\""+ws+"\",\"orgId\":\""+orgId+"\",\"purpose\":\"test\",\"displayName\":\"test\",\"reqIndefinite\":true,\"gpu\":{}}"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void approvalGrantColumnsCannotExistWithoutAnApproval() {
        long request = request();
        jdbc.update("update gpu_request_details set granted_lease_hours=1,granted_priority=0 where request_id=?",request);
        assertThatThrownBy(() -> jdbc.execute("set constraints all immediate")).isInstanceOf(RuntimeException.class);
    }

    @Test
    void aStaleDeleteJobCannotDetachForAFutureReplacementSchedule() {
        long id = allocated(); long vm = vm(); mutations.attach(owner, publicId(id), vmPublicId(vm), null); run(id);
        jdbc.update("update vms set delete_kind='ADMIN',delete_scheduled_for=now()+interval '2 days',delete_requested_at=now() where id=?", vm);
        entityManager.clear();
        assertThat(mutations.prepareVmDeletion(vm)).isFalse();
        assertThat(store.allocation(id).orElseThrow().connectionStatus()).isEqualTo(GpuConnectionStatus.ATTACHED);
        assertThat(store.allocation(id).orElseThrow().operationId()).isNull();
    }

    @Test
    void canceledDeletionDoesNotExecuteAQueuedGpuDetach() {
        long id = allocated(); long vm = vm(); mutations.attach(owner, publicId(id), vmPublicId(vm), null); run(id);
        jdbc.update("update vms set status='DELETING',delete_kind='FORCE',delete_scheduled_for=now(),delete_requested_at=now() where id=?", vm);
        entityManager.clear();
        assertThat(mutations.prepareVmDeletion(vm)).isFalse();
        jdbc.update("update vms set status='STOPPED',delete_kind=null,delete_scheduled_for=null,delete_requested_at=null where id=?", vm);
        run(id);
        assertThat(store.allocation(id).orElseThrow().connectionStatus()).isEqualTo(GpuConnectionStatus.ATTACHED);
        assertThat(store.allocation(id).orElseThrow().operationId()).isNull();
        assertThat(config.get()).containsKey("hostpci0");
    }

    @Test
    void aVmOnAnotherNodeIsRefusedBeforeItIsStoppedOrLocked() {
        long id = allocated(); long vm = vm();
        long otherNode = jdbc.queryForObject("""
                insert into nodes(name,api_host,cpu_threads,memory_mb,vm_bridge,storage)
                values (?,'example.invalid',8,8192,'vmbr2','local') returning id
                """, Long.class, "other-" + UUID.randomUUID());
        jdbc.update("update vms set node_id=? where id=?", otherNode, vm); entityManager.clear();
        assertThatThrownBy(() -> mutations.attach(owner, publicId(id), vmPublicId(vm), null)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select pending_power_action from vms where id=?", String.class, vm)).isNull();
        assertThat(jdbc.queryForObject("select node_id from vms where id=?", Long.class, vm)).isEqualTo(otherNode);
        verify(proxmox, never()).shutdown(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void gpuReturnDoesNotRequireTheHolderToRegainTheVmsAccessGrant() {
        long id = allocated(); long vm = vm(); mutations.attach(owner, publicId(id), vmPublicId(vm), null); run(id);
        jdbc.update("delete from resource_access_grants where resource_type='VM' and resource_id=?", vm);
        mutations.release(owner, publicId(id), null); run(id);
        assertThat(store.allocation(id).orElseThrow().status()).isEqualTo(GpuAllocationStatus.RELEASED);
    }

    @Test
    void anExpiredQueuedAttachmentNeverTouchesTheVm() {
        long id = allocated(); long vm = vm(); mutations.attach(owner, publicId(id), vmPublicId(vm), null);
        jdbc.update("update gpu_allocations set lease_ends_at=now()-interval '1 minute' where id=?", id);
        run(id);
        assertThat(store.allocation(id).orElseThrow().connectionStatus()).isEqualTo(GpuConnectionStatus.NONE);
        assertThat(store.allocation(id).orElseThrow().operationId()).isNull();
        verify(proxmox, never()).currentVmStatus(anyString(), anyString(), anyInt());
        mutations.expire(id);
        assertThat(store.allocation(id).orElseThrow().status()).isEqualTo(GpuAllocationStatus.RELEASED);
    }

    @Test
    void aPowerTimeoutKeepsTheClaimUntilEveryRemoteDispatchHasFinished() {
        long id = allocated(); long vm = vm();
        UUID worker = UUID.randomUUID();
        jdbc.update("update vms set pending_power_action='START',pending_power_action_at=now() where id=?", vm);
        assertThat(vms.claimPowerWorker(vm, "START", worker)).isEqualTo(1);
        powerGuard.dispatch(vm, worker, () -> "still-running");
        when(proxmox.taskStatus(anyString(), anyString(), anyString())).thenReturn(new TaskStatus("running", null, "still-running"));
        powerGuard.finish(vm, worker);
        jdbc.update("update vms set pending_power_action_at=now()-interval '2 hours' where id=?", vm);
        vms.clearStalePowerActionClaims(clock.instant().minus(Duration.ofMinutes(10)), clock.instant());
        assertThat(jdbc.queryForObject("select power_operation_id from vms where id=?", UUID.class, vm)).isEqualTo(worker);
        entityManager.clear();
        assertThatThrownBy(() -> mutations.attach(owner, publicId(id), vmPublicId(vm), null)).isInstanceOf(ApiException.class);
        when(proxmox.taskStatus(anyString(), anyString(), anyString())).thenReturn(new TaskStatus("stopped", "OK", "still-running"));
        powerGuard.finish(vm, worker);
        assertThat(jdbc.queryForObject("select power_operation_id from vms where id=?", UUID.class, vm)).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aLostCompletionCasRollsBackAttachmentAndKeepsTheVmClaim() {
        long[] ids = transactionTemplate.execute(ignored -> new long[] {gpu(), allocation(48), vm()});
        scheduler.allocate(); mutations.attach(owner, publicId(ids[1]), vmPublicId(ids[2]), null);
        UUID operation = store.allocation(ids[1]).orElseThrow().operationId(); UUID worker = UUID.randomUUID();
        jdbc.update("update gpu_operations set phase='RUNNING',worker_id=? where id=?", worker, operation);
        jdbc.execute("create function reject_test_gpu_completion() returns trigger language plpgsql as $$ begin if new.phase='DONE' then return null; end if; return new; end $$");
        jdbc.execute("create trigger reject_test_gpu_completion before update on gpu_operations for each row execute function reject_test_gpu_completion()");
        try {
            assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(operations, "complete", operations.load(operation), worker, true))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(store.allocation(ids[1]).orElseThrow().connectionStatus()).isEqualTo(GpuConnectionStatus.ATTACHING);
            assertThat(jdbc.queryForObject("select power_operation_id from vms where id=?", UUID.class, ids[2])).isEqualTo(operation);
        } finally {
            jdbc.execute("drop trigger reject_test_gpu_completion on gpu_operations");
            jdbc.execute("drop function reject_test_gpu_completion()");
            jdbc.update("update gpu_operations set phase='DONE' where id=?", operation);
            jdbc.update("update gpu_allocations set connection_status='NONE',vm_id=null,operation_id=null where id=?", ids[1]);
            jdbc.update("update vms set pending_power_action=null,power_operation_id=null where id=?", ids[2]);
            retire(new long[]{ids[0],ids[1]});
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void priorityDowngradeAndAllocationUseTheSameTransactionLock() throws Exception {
        long[] ids = transactionTemplate.execute(ignored -> new long[] {gpu(), allocation(48), allocation(48)});
        var changed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var priority = executor.submit(() -> transactionTemplate.executeWithoutResult(ignored -> {
                mutations.priority(admin, publicId(ids[1]), -1, "let the next request proceed", null);
                changed.countDown(); await(release);
            }));
            assertThat(changed.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try {
                // The scheduler must skip the held advisory lock, not select the old queue order and wait on its first row.
                executor.submit(scheduler::allocate).get(2, java.util.concurrent.TimeUnit.SECONDS);
            } finally { release.countDown(); }
            priority.get(10, java.util.concurrent.TimeUnit.SECONDS);
            scheduler.allocate();
            assertThat(store.allocation(ids[1]).orElseThrow().status()).isEqualTo(GpuAllocationStatus.QUEUED);
            assertThat(store.allocation(ids[2]).orElseThrow().status()).isEqualTo(GpuAllocationStatus.ALLOCATED);
        } finally { release.countDown(); retire(ids); }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void keepAndReviewOpeningSerializeOnTheAllocationBeforeTheReviewRow() throws Exception {
        long[] ids = transactionTemplate.execute(ignored -> new long[] {gpu(), allocation(48)});
        scheduler.allocate();
        jdbc.update("update gpu_allocations set unattached_since=now()-interval '13 hours' where id=?", ids[1]);
        var evidence = Map.<String,Object>of("unattachedSince", store.allocation(ids[1]).orElseThrow().unattachedSince().toString());
        reviews.open(ids[1], "UNATTACHED", evidence);
        UUID reviewId = jdbc.queryForObject("select public_id from gpu_reclaim_reviews where allocation_id=?", UUID.class, ids[1]);
        var kept = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var opening = new java.util.concurrent.CountDownLatch(1);
        var pid = new java.util.concurrent.atomic.AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var keep = executor.submit(() -> transactionTemplate.executeWithoutResult(ignored -> {
                reviews.decide(admin, reviewId, GpuReviewDecision.KEEP, "ongoing work", null);
                kept.countDown(); await(release);
            }));
            assertThat(kept.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var open = executor.submit(() -> transactionTemplate.executeWithoutResult(ignored -> {
                pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class)); opening.countDown();
                reviews.open(ids[1], "UNATTACHED", evidence);
            }));
            assertThat(opening.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            try { awaitAllocationLock(pid.get()); } finally { release.countDown(); }
            keep.get(10, java.util.concurrent.TimeUnit.SECONDS); open.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select count(*) from gpu_reclaim_reviews where allocation_id=? and decision is null", Long.class, ids[1])).isZero();
        } finally { release.countDown(); retire(ids); }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void dispatchCannotPassAnErrorTransitionCommittedWhileItWaitsForTheOperationRow() throws Exception {
        long[] ids = transactionTemplate.execute(ignored -> new long[] {gpu(), allocation(48), vm()}); scheduler.allocate();
        mutations.attach(owner, publicId(ids[1]), vmPublicId(ids[2]), null);
        UUID operation = store.allocation(ids[1]).orElseThrow().operationId(); UUID worker = UUID.randomUUID();
        jdbc.update("update gpu_operations set phase='RUNNING',worker_id=? where id=?", worker, operation);
        var errored = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var error = executor.submit(() -> transactionTemplate.executeWithoutResult(ignored -> {
                jdbc.update("update gpu_operations set phase='ERROR' where id=?", operation); errored.countDown(); await(release);
            }));
            assertThat(errored.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var mark = executor.submit(() -> ReflectionTestUtils.invokeMethod(operations, "markDispatch", operations.load(operation), worker));
            try { assertThatThrownBy(() -> mark.get(250, java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class); }
            finally { release.countDown(); }
            error.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(() -> mark.get(10, java.util.concurrent.TimeUnit.SECONDS)).isInstanceOf(java.util.concurrent.ExecutionException.class);
            assertThat(jdbc.queryForObject("select remote_dispatch_pending from gpu_operations where id=?", Boolean.class, operation)).isFalse();
        } finally {
            release.countDown();
            jdbc.update("update gpu_operations set phase='DONE' where id=?", operation);
            jdbc.update("update gpu_allocations set connection_status='NONE',vm_id=null,operation_id=null where id=?", ids[1]);
            jdbc.update("update vms set pending_power_action=null,power_operation_id=null where id=?", ids[2]);
            retire(new long[]{ids[0],ids[1]});
        }
    }

    private void awaitAllocationLock(int pid) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            var state = jdbc.queryForMap("select query,wait_event_type from pg_stat_activity where pid=?", pid);
            if ("Lock".equals(state.get("wait_event_type")) && String.valueOf(state.get("query")).contains("select * from gpu_allocations")) { return; }
            Thread.sleep(10);
        }
        throw new AssertionError("review opening did not wait on the allocation row");
    }
    private static void await(java.util.concurrent.CountDownLatch latch) {
        try { if (!latch.await(10, java.util.concurrent.TimeUnit.SECONDS)) { throw new AssertionError("coordination timed out"); } }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private void retire(long[] ids) {
        transactionTemplate.executeWithoutResult(ignored -> {
            for (int i=1;i<ids.length;i++) { jdbc.update("update gpu_allocations set status=case when status='QUEUED' then 'CANCELED'::gpu_allocation_status else 'RELEASED'::gpu_allocation_status end where id=?", ids[i]); }
            jdbc.update("update gpus set status='RETIRED' where id=?", ids[0]);
        });
    }

    @Test
    void reenablingDeletionProtectionPreventsGpuTeardownAtDestroyTime() {
        long id = allocated(); long vm = vm(); mutations.attach(owner, publicId(id), vmPublicId(vm), null); run(id);
        jdbc.update("update vms set delete_kind='ADMIN',delete_scheduled_for=now(),delete_requested_at=now() where id=?", vm);
        jdbc.update("insert into vm_settings(vm_id,key,value) values (?,'deletion_protection','true'::jsonb)", vm);
        entityManager.clear();
        deleteVmJob.deleteVm(vm);
        assertThat(store.allocation(id).orElseThrow().connectionStatus()).isEqualTo(GpuConnectionStatus.ATTACHED);
        assertThat(store.allocation(id).orElseThrow().operationId()).isNull();
        assertThat(jdbc.queryForObject("select status::text from provisioning_tasks where vm_id=? and kind='DELETE'", String.class, vm)).isEqualTo("NEEDS_ADMIN");
        verify(proxmox, never()).shutdown(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void protectionEnabledAfterGpuDeleteIntentStopsTheUnstartedDetach() {
        long id = allocated(); long vm = vm(); mutations.attach(owner, publicId(id), vmPublicId(vm), null); run(id);
        jdbc.update("update vms set status='DELETING',delete_kind='FORCE',delete_scheduled_for=now(),delete_requested_at=now() where id=?", vm);
        entityManager.clear(); assertThat(mutations.prepareVmDeletion(vm)).isFalse();
        jdbc.update("insert into vm_settings(vm_id,key,value) values (?,'deletion_protection','true'::jsonb)", vm);
        run(id);
        assertThat(store.allocation(id).orElseThrow().connectionStatus()).isEqualTo(GpuConnectionStatus.ATTACHED);
        assertThat(store.allocation(id).orElseThrow().operationId()).isNull();
        verify(proxmox, never()).shutdown(anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void inactiveNodeCapacityIsNotAdvertisedAsAvailable() {
        long card = gpu();
        jdbc.update("update nodes set status='OFFLINE' where id=?", node); entityManager.clear();
        assertThat(query.gpuView(store.gpu(card).orElseThrow()).available()).isFalse();
        long id = allocation(48); scheduler.allocate();
        assertThat(store.allocation(id).orElseThrow().status()).isEqualTo(GpuAllocationStatus.QUEUED);
    }

    @Test
    void reconciliationCannotOverwriteTheVmAfterItsClaimWasReplaced() {
        long id = allocated(); long vm = vm(); mutations.attach(owner, publicId(id), vmPublicId(vm), null);
        UUID operation = store.allocation(id).orElseThrow().operationId(); UUID replacement = UUID.randomUUID();
        jdbc.update("update gpu_operations set phase='ERROR' where id=?", operation);
        jdbc.update("update gpu_allocations set connection_status='ERROR' where id=?", id);
        jdbc.update("update vms set power_operation_id=?,status='RUNNING' where id=?", replacement, vm); entityManager.clear();
        assertThatThrownBy(() -> reconciliation.reconcile(admin, publicId(id), "readback", null))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException)e).getStatus().value()).isEqualTo(409);
        assertThat(jdbc.queryForObject("select power_operation_id from vms where id=?", UUID.class, vm)).isEqualTo(replacement);
        assertThat(jdbc.queryForObject("select status::text from vms where id=?", String.class, vm)).isEqualTo("RUNNING");
        assertThat(store.allocation(id).orElseThrow().connectionStatus()).isEqualTo(GpuConnectionStatus.ERROR);
    }

    @Test
    void requestVmSelectionMasksForeignUngrantedsExactlyLikeMissingVms() throws Exception {
        long vm = vm();
        long foreignWorkspace = jdbc.queryForObject("insert into workspaces(kind,name) values ('TEAM','other GPU workspace') returning id", Long.class);
        jdbc.update("delete from resource_access_grants where resource_type='VM' and resource_id=?", vm);
        jdbc.update("update vms set workspace_id=? where id=?", foreignWorkspace, vm); entityManager.clear();
        String[] details = new String[2]; int i = 0;
        for (UUID target : List.of(vmPublicId(vm), UUID.randomUUID())) {
            String body = json.writeValueAsString(Map.of("type","GPU","workspaceId",SeedFixtures.publicId(jdbc,"workspaces",workspace),
                    "orgId",SeedFixtures.publicId(jdbc,"orgs",org),"purpose","test","displayName","test","reqIndefinite",true,
                    "gpu",Map.of("leaseHours",24,"vmId",target)));
            var result = mvc.perform(post("/api/v1/requests").with(authentication(auth(owner))).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isNotFound()).andReturn();
            var problem = json.readTree(result.getResponse().getContentAsString());
            assertThat(problem.get("code").asString()).isEqualTo("RESOURCE_NOT_FOUND");
            details[i++] = problem.get("detail").asString();
        }
        assertThat(details[0]).isEqualTo(details[1]);
    }

    private long gpu() {
        return jdbc.queryForObject("insert into gpus(node_id,mapping_name,model,vram_mb,status) values (?,?,'Example GPU',32768,'ACTIVE') returning id",Long.class,node,"example-"+UUID.randomUUID());
    }
    private long request() {
        long request=jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values ('GPU',?,?,?,'test','GPU test') returning id",Long.class,workspace,org,owner.id());
        jdbc.update("insert into gpu_request_details(request_id,lease_hours) values (?,48)",request);return request;
    }
    private long allocation(int hours) {
        long request=request();
        jdbc.update("update requests set status='APPROVED' where id=?",request);
        jdbc.update("insert into request_reviews(request_id,reviewer_id,decision) values (?,?,'APPROVE')",request,admin.id());
        jdbc.update("update gpu_request_details set granted_lease_hours=?,granted_priority=0 where request_id=?",hours,request);
        long id=jdbc.queryForObject("insert into gpu_allocations(request_id,workspace_id,org_id,name,granted_lease_hours) values (?,?,?,'GPU test',?) returning id",Long.class,request,workspace,org,hours);
        jdbc.update("insert into resource_access_grants(resource_type,resource_id,grantee_type,user_id,role) values ('GPU',?,'USER',?,'OWNER')",id,owner.id());return id;
    }
    private long allocated() { gpu();long id=allocation(48);scheduler.allocate();return id; }
    private long vm() {
        long image=jdbc.queryForObject("select min(id) from os_images",Long.class);
        long request=RequestFixtures.insertVmRequest(jdbc,workspace,org,owner.id(),"GPU VM",image);
        long id=jdbc.queryForObject("""
                insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status,proxmox_vmid)
                values (?,?,?,?,'GPU VM',?, ?,2,2048,10,'STOPPED',nextval('vmid_seq')) returning id
                """,Long.class,node,workspace,org,request,"gpu-"+UUID.randomUUID(),image);
        jdbc.update("insert into resource_access_grants(resource_type,resource_id,grantee_type,user_id,role) values ('VM',?,'USER',?,'OWNER')",id,owner.id());return id;
    }
    private void run(long allocation) { entityManager.flush(); entityManager.clear(); operations.run(store.allocation(allocation).orElseThrow().operationId()); entityManager.flush(); entityManager.clear(); }
    private UUID publicId(long id) { return SeedFixtures.publicId(jdbc,"gpu_allocations",id); }
    private UUID vmPublicId(long id) { return SeedFixtures.publicId(jdbc,"vms",id); }
    private long reviewCount(long id) { return jdbc.queryForObject("select count(*) from gpu_reclaim_reviews where allocation_id=?",Long.class,id); }
    private UsernamePasswordAuthenticationToken auth(AuthenticatedUser actor) {
        return UsernamePasswordAuthenticationToken.authenticated(actor,"test",List.of(new SimpleGrantedAuthority("ROLE_"+actor.role().name())));
    }
}
