package kr.ac.pusan.pickle.security.institution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InstitutionResourceRuntimeTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private InstitutionActorProvider actors;
    @Autowired private kr.ac.pusan.pickle.admin.VmApprovalContextContributor vmContext;
    @Autowired private kr.ac.pusan.pickle.gpu.GpuApprovalContextContributor gpuContext;
    @Autowired private kr.ac.pusan.pickle.request.RequestRepository requests;
    @Autowired private kr.ac.pusan.pickle.admin.VmPeriodService periods;
    @Autowired private kr.ac.pusan.pickle.orgs.OrgAdministrationLock administrationLock;
    @Autowired private kr.ac.pusan.pickle.admin.bulk.AdminBulkChangeService bulk;
    @Autowired private tools.jackson.databind.ObjectMapper mapper;
    private long orgA;
    private long orgB;
    private long workspaceA;
    private long workspaceB;
    private long requester;

    @BeforeEach
    void targets() {
        orgA = jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class, "Source " + UUID.randomUUID());
        orgB = jdbc.queryForObject("insert into orgs(name) values(?) returning id", Long.class, "Target " + UUID.randomUUID());
        workspaceA = workspace(); workspaceB = workspace();
        requester = user(UserRole.USER);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspaceA, requester);
    }

    @Test
    void selectedVmSupportScopesRowsBeforePageAndCountAcrossInstitutions() throws Exception {
        long first = vm(orgB, workspaceA); vm(orgA, workspaceB); long second = vm(orgA, workspaceA);
        String token = token(Set.of(InstitutionWork.VM_METADATA_SUPPORT_READ), workspaceA);
        mvc.perform(get("/api/v1/admin/vms?size=1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(get("/api/v1/admin/vms/" + publicId("vms", first)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(publicId("vms", first).toString()));
        mvc.perform(get("/api/v1/admin/vms/" + publicId("vms", second)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void unrelatedManagerWorkDoesNotGrantVmMetadataOrMutation() throws Exception {
        long vm = vm(orgA, workspaceA);
        String token = token(Set.of(InstitutionWork.WORKSPACE_INFO_UPDATE), workspaceA);
        mvc.perform(get("/api/v1/admin/vms").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/vms/" + publicId("vms", vm) + "/period")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\":\"2099-12-31\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void workspaceInfoOnlyReadbackDoesNotFabricateEmptyMembersOrCounts() throws Exception {
        String token = token(Set.of(InstitutionWork.WORKSPACE_INFO_UPDATE), workspaceA);
        mvc.perform(patch("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceA))
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated reference\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Updated reference"))
                .andExpect(jsonPath("$.memberCount").isEmpty()).andExpect(jsonPath("$.vmCount").isEmpty())
                .andExpect(jsonPath("$.members").isEmpty()).andExpect(jsonPath("$.canUpdateInfo").value(true))
                .andExpect(jsonPath("$.canManageMembers").value(false));
        mvc.perform(get("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceA))
                        .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.metadataVisible").value(false)).andExpect(jsonPath("$.members").isEmpty());
    }

    @Test
    void workspaceReadDoesNotGrantInfoWritesOrAnotherWorkspace() throws Exception {
        String token = token(Set.of(InstitutionWork.WORKSPACE_SUPPORT_READ), workspaceA);
        mvc.perform(get("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceA))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.members.length()").value(1)).andExpect(jsonPath("$.canUpdateInfo").value(false));
        mvc.perform(get("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceB))
                        .header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceA))
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Denied\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void fullDisplayWithoutConvertedSourceDoesNotRestoreLegacySupport() throws Exception {
        long account = user(UserRole.ORG_ADMIN);
        String token = jwt.createAccessToken(users.findById(account).orElseThrow());
        mvc.perform(get("/api/v1/admin/vms").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/workspaces").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void aclSupportReadIsScopedAndDoesNotBecomePersonalVmContentAccess() throws Exception {
        long allowed = vm(orgB, workspaceA); long outside = vm(orgB, workspaceB);
        String token = token(Set.of(InstitutionWork.VM_ACL_SUPPORT_READ), workspaceA);
        mvc.perform(get("/api/v1/admin/resource-access/VM/" + publicId("vms", allowed))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canManageAccess").value(false));
        mvc.perform(get("/api/v1/admin/resource-access/VM/" + publicId("vms", outside))
                        .header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/vms/" + publicId("vms", allowed)).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void unchangedDisplayRoleLosesAnOldWorkOnTheNextBearerRequest() throws Exception {
        long account = user(UserRole.ORG_MANAGER);
        InstitutionAssignment stored = grant(account, Set.of(InstitutionWork.WORKSPACE_INFO_UPDATE), workspaceA);
        String token = jwt.createAccessToken(users.findById(account).orElseThrow());
        mvc.perform(get("/api/v1/admin/workspaces").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        var next = InstitutionAssignment.selected(publicId("users", account), publicId("orgs", orgA),
                Set.of(InstitutionWork.VM_POWER), Map.of(InstitutionWorkGroup.VM,
                        InstitutionWorkspaceScope.listed(Set.of(publicId("workspaces", workspaceA)))), 0);
        var store = new InstitutionAssignmentStore(jdbc, new InstitutionAssignmentCodec());
        new TransactionTemplate(transactionManager).execute(status -> store.save(next, stored.revision()));
        mvc.perform(get("/api/v1/admin/workspaces").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("select role::text from users where id=?", String.class, account)).isEqualTo("ORG_MANAGER");
    }

    @Test
    void periodCommandDoesNotOpenMetadataReadbackOrPublicationProof() throws Exception {
        long target = vm(orgA, workspaceA);
        String token = token(Set.of(InstitutionWork.VM_PERIOD), workspaceA);
        mvc.perform(patch("/api/v1/admin/vms/" + publicId("vms", target) + "/period")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endDate\":\"2099-12-31\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.endDate").value("2099-12-31"))
                .andExpect(jsonPath("$.vcpu").isEmpty()).andExpect(jsonPath("$.workspaceId").isNotEmpty())
                .andExpect(jsonPath("$.ipAddress").isEmpty()).andExpect(jsonPath("$.publications").isEmpty())
                .andExpect(jsonPath("$.passwordAvailable").isEmpty()).andExpect(jsonPath("$.gpu").isEmpty());
        mvc.perform(get("/api/v1/admin/vms/" + publicId("vms", target))
                        .header("Authorization", "Bearer " + token)).andExpect(status().isOk())
                .andExpect(jsonPath("$.metadataVisible").value(false)).andExpect(jsonPath("$.vcpu").isEmpty());
    }

    @Test
    void metadataOnlyGpuDoesNotRevealSamplesAndObserveOnlyDoesNotRevealMetadata() throws Exception {
        long target = allocation(orgB, workspaceA);
        String metadata = token(Set.of(InstitutionWork.GPU_METADATA_SUPPORT_READ), workspaceA);
        mvc.perform(get("/api/v1/admin/gpu-allocations/" + publicId("gpu_allocations", target))
                        .header("Authorization", "Bearer " + metadata))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grantedLeaseHours").value(48))
                .andExpect(jsonPath("$.ownerNames").isArray()).andExpect(jsonPath("$.utilizationPercent").isEmpty());
        String observe = token(Set.of(InstitutionWork.GPU_OBSERVE_SUPPORT_READ), workspaceA);
        mvc.perform(get("/api/v1/admin/gpu-allocations/" + publicId("gpu_allocations", target))
                        .header("Authorization", "Bearer " + observe))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grantedLeaseHours").isEmpty())
                .andExpect(jsonPath("$.ownerNames").isEmpty()).andExpect(jsonPath("$.gpu").isEmpty())
                .andExpect(jsonPath("$.utilizationPercent").value(42.0));
        jdbc.update("update gpu_utilization_samples set confidence=0 where allocation_id=?", target);
        mvc.perform(get("/api/v1/admin/gpu-allocations/" + publicId("gpu_allocations", target))
                        .header("Authorization", "Bearer " + observe))
                .andExpect(status().isOk()).andExpect(jsonPath("$.utilizationPercent").isEmpty());
    }

    @Test
    void gpuPeriodOnlyReceivesItsOwnChangedLeaseWithoutOwnerOrSampleReadback() throws Exception {
        long target = allocation(orgA, workspaceA);
        String token = token(Set.of(InstitutionWork.GPU_PERIOD), workspaceA);
        mvc.perform(post("/api/v1/admin/gpu-allocations/" + publicId("gpu_allocations", target) + "/extend")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\":2,\"reason\":\"Synthetic extension\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.grantedLeaseHours").value(50))
                .andExpect(jsonPath("$.leaseEndsAt").isNotEmpty()).andExpect(jsonPath("$.ownerNames").isEmpty())
                .andExpect(jsonPath("$.gpu").isEmpty()).andExpect(jsonPath("$.utilizationPercent").isEmpty());
    }

    @Test
    void vmDecisionReferencesStayInTheActualRequestAndNeverBecomeHeadroom() {
        long target = vm(orgA, workspaceA); vm(orgB, workspaceA); vm(orgA, workspaceB);
        long request = jdbc.queryForObject("select request_id from vms where id=?", Long.class, target);
        var actor = actor(Set.of(InstitutionWork.VM_REQUEST_DECIDE), workspaceA);
        var context = vmContext.contribute(requests.findById(request).orElseThrow(), java.util.List.of(workspaceA, workspaceB), actor).vm();
        assertThat(context.orgHeadroom()).isNull();
        assertThat(context.applicantResources().totals()).isNull();
        assertThat(context.applicantResources().activeVms()).hasSize(1);
        assertThat(context.applicantResources().activeVms().getFirst().id()).isEqualTo(publicId("vms", target));
        assertThat(context.applicantResources().activeVms().getFirst().vcpu()).isNull();
        assertThat(context.requestedImage()).isNotNull();
        long llmRequest = RequestFixtures.insertLlmKeyRequest(jdbc, workspaceA, orgA, requester, "Synthetic LLM");
        var other = vmContext.contribute(requests.findById(llmRequest).orElseThrow(), java.util.List.of(workspaceA), actor).vm();
        assertThat(other.applicantResources().activeVms()).isNull();
        assertThat(other.orgHeadroom()).isNull();
    }

    @Test
    void listedVmMetadataDoesNotPretendItsSubsetIsInstitutionHeadroom() {
        long target = vm(orgA, workspaceA); vm(orgA, workspaceB);
        long request = jdbc.queryForObject("select request_id from vms where id=?", Long.class, target);
        var actor = actor(Set.of(InstitutionWork.VM_METADATA_SUPPORT_READ), workspaceA);
        var context = vmContext.contribute(requests.findById(request).orElseThrow(), java.util.List.of(workspaceA, workspaceB), actor).vm();
        assertThat(context.applicantResources().activeVms()).hasSize(1);
        assertThat(context.applicantResources().totals().vcpu()).isEqualTo(1);
        assertThat(context.orgHeadroom()).isNull();
    }

    @Test
    void listedGpuRequestReferenceDoesNotOpenGlobalQueueTotals() {
        long target = allocation(orgA, workspaceA);
        long request = jdbc.queryForObject("select request_id from gpu_allocations where id=?", Long.class, target);
        var actor = actor(Set.of(InstitutionWork.GPU_REQUEST_READ), workspaceA);
        var context = gpuContext.contribute(requests.findById(request).orElseThrow(), java.util.List.of(workspaceA), actor).gpu();
        assertThat(context.availableCards()).isNotNull();
        assertThat(context.queuedAllocations()).isNull();
        var outside = actor(Set.of(InstitutionWork.GPU_REQUEST_READ), workspaceB);
        assertThat(gpuContext.contribute(requests.findById(request).orElseThrow(), java.util.List.of(workspaceA), outside).gpu().availableCards()).isNull();
    }

    @Test
    void allSupportMetadataPreservesLegacyHeadroomAndGlobalQueueMeaning() {
        long target = vm(orgB, workspaceA);
        long request = jdbc.queryForObject("select request_id from vms where id=?", Long.class, target);
        var allVm = actorAll(Set.of(InstitutionWork.VM_METADATA_SUPPORT_READ));
        assertThat(vmContext.contribute(requests.findById(request).orElseThrow(), java.util.List.of(workspaceA), allVm).vm().orgHeadroom()).isNotNull();
        var directOtherInstitution = actorAll(Set.of(InstitutionWork.VM_METADATA_READ));
        assertThat(vmContext.contribute(requests.findById(request).orElseThrow(), java.util.List.of(workspaceA), directOtherInstitution).vm().orgHeadroom()).isNull();
        long allocation = allocation(orgB, workspaceA);
        long gpuRequest = jdbc.queryForObject("select request_id from gpu_allocations where id=?", Long.class, allocation);
        var global = actorAll(Set.of(InstitutionWork.GPU_METADATA_SUPPORT_READ, InstitutionWork.GPU_REQUEST_SUPPORT_READ));
        var context = gpuContext.contribute(requests.findById(gpuRequest).orElseThrow(), java.util.List.of(workspaceA), global).gpu();
        assertThat(context.availableCards()).isNotNull();
        assertThat(context.queuedAllocations()).isEqualTo(jdbc.queryForObject("select count(*) from gpu_allocations where status='QUEUED'", Long.class));
        var direct = actorAll(Set.of(InstitutionWork.GPU_METADATA_READ, InstitutionWork.GPU_REQUEST_READ));
        assertThat(gpuContext.contribute(requests.findById(gpuRequest).orElseThrow(), java.util.List.of(workspaceA), direct).gpu().queuedAllocations()).isNull();
    }

    @Test
    void periodMutationRechecksChangedWorkAfterTheAdministrationBarrier() throws Exception {
        long target = vm(orgA, workspaceA);
        long account = user(UserRole.ORG_MANAGER);
        var stored = grant(account, Set.of(InstitutionWork.VM_PERIOD), workspaceA);
        var stale = actors.authenticate(publicId("users", account), 0).orElseThrow();
        var replacement = InstitutionAssignment.selected(publicId("users", account), publicId("orgs", orgA),
                Set.of(InstitutionWork.VM_POWER), Map.of(InstitutionWorkGroup.VM,
                        InstitutionWorkspaceScope.listed(Set.of(publicId("workspaces", workspaceA)))), 0);
        java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>> task = new java.util.concurrent.atomic.AtomicReference<>();
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).execute(status -> {
                administrationLock.acquire();
                task.set(executor.submit(() -> periods.updatePeriod(stale, publicId("vms", target),
                        new kr.ac.pusan.pickle.admin.dto.VmPeriodUpdateRequest(java.time.LocalDate.of(2099, 12, 31), false, null), "127.0.0.1")));
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while (!Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_locks where locktype='advisory' and classid=1129074511 and objid=1 and not granted)", Boolean.class))) {
                    if (System.nanoTime() > deadline) { throw new AssertionError("period mutation did not wait on administration barrier"); }
                    try { Thread.sleep(10); } catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                }
                new InstitutionAssignmentStore(jdbc, new InstitutionAssignmentCodec()).save(replacement, stored.revision());
                return null;
            });
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> task.get().get(10, java.util.concurrent.TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.ExecutionException.class)
                    .hasCauseInstanceOf(kr.ac.pusan.pickle.common.error.ApiException.class);
        }
        assertThat(jdbc.queryForObject("select end_date from vms where id=?", java.time.LocalDate.class, target)).isNull();
    }

    @Test
    void terminalMetadataDoesNotGrantPersonalTerminalAndUnrelatedWorkCannotListSessions() throws Exception {
        long target = vm(orgA, workspaceA);
        String token = token(Set.of(InstitutionWork.VM_TERMINAL_SESSION_READ), workspaceA);
        mvc.perform(get("/api/v1/admin/terminal-sessions").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/vms/" + publicId("vms", target) + "/terminal-sessions")
                        .header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        String unrelated = token(Set.of(InstitutionWork.VM_METADATA_SUPPORT_READ), workspaceA);
        mvc.perform(get("/api/v1/admin/terminal-sessions").header("Authorization", "Bearer " + unrelated)).andExpect(status().isForbidden());
    }

    @Test
    void domainMetadataDoesNotGrantRawRecordsOrVmPublicationKinds() throws Exception {
        long external = domain("EXTERNAL", null, orgA, workspaceA);
        long custom = domain("CUSTOM", vm(orgA, workspaceA), orgA, workspaceA);
        String token = token(Set.of(InstitutionWork.DOMAIN_METADATA_READ), workspaceA);
        mvc.perform(get("/api/v1/admin/domains/" + publicId("domains", external)).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/domains/" + publicId("domains", custom)).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/domains/" + publicId("domains", external) + "/records").header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        String observe = token(Set.of(InstitutionWork.DOMAIN_OBSERVE), workspaceA);
        mvc.perform(get("/api/v1/admin/domains/" + publicId("domains", external) + "/records").header("Authorization", "Bearer " + observe)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/domains/" + publicId("domains", custom) + "/records").header("Authorization", "Bearer " + observe)).andExpect(status().isNotFound());
    }

    @Test
    void renewalOnlyConfirmsItsOwnChangedFieldWithoutDomainMetadata() throws Exception {
        long external = domain("EXTERNAL", null, orgA, workspaceA);
        String token = token(Set.of(InstitutionWork.DOMAIN_RENEWAL), workspaceA);
        mvc.perform(patch("/api/v1/admin/domains/" + publicId("domains", external) + "/renewal")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"renewDueAt\":\"2099-12-31T00:00:00Z\",\"reason\":\"Synthetic renewal\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.renewDueAt").value("2099-12-31T00:00:00Z"))
                .andExpect(jsonPath("$.workspaceId").isNotEmpty()).andExpect(jsonPath("$.metadataVisible").value(false))
                .andExpect(jsonPath("$.dnsStatus").isEmpty()).andExpect(jsonPath("$.certificateStatus").isEmpty());
    }

    @Test
    void sharedCertificateIsAnExplicitGlobalReadWithoutWorkspaceConditions() throws Exception {
        String token = token(Set.of(InstitutionWork.SHARED_CERTIFICATE_READ), workspaceB);
        mvc.perform(get("/api/v1/admin/certificates").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/admin/domains").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
    }

    @Test
    void workspaceMemberWorkDoesNotBlanketDenySystemAccountsOrBorrowOwnerWork() throws Exception {
        long system = user(UserRole.SYS_ADMIN);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'MEMBER')", workspaceA, system);
        String token = token(Set.of(InstitutionWork.WORKSPACE_MEMBERS_MANAGE, InstitutionWork.WORKSPACE_OWNERS_MANAGE), workspaceA);
        mvc.perform(patch("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceA) + "/members/" + publicId("users", system))
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"OWNER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("OWNER"));
        long otherOwner = user(UserRole.USER);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'OWNER')", workspaceA, otherOwner);
        long account = user(UserRole.ORG_MANAGER);
        grant(account, Set.of(InstitutionWork.WORKSPACE_MEMBERS_MANAGE), workspaceA);
        grantSource(account, orgB, Set.of(InstitutionWork.WORKSPACE_OWNERS_MANAGE), workspaceB);
        String split = jwt.createAccessToken(users.findById(account).orElseThrow());
        mvc.perform(patch("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceA) + "/members/" + publicId("users", otherOwner))
                        .header("Authorization", "Bearer " + split).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"MEMBER\"}"))
                .andExpect(status().isForbidden());
    }

    private long domain(String kind, Long vm, long org, long workspace) {
        return jdbc.queryForObject("insert into domains(vm_id,kind,fqdn,status,workspace_id,org_id,renew_due_at) values(?,?::domain_kind,?,'ACTIVE',?,?,case when ?='EXTERNAL' then now()+interval '30 days' else null end) returning id",
                Long.class, vm, kind, UUID.randomUUID()+".example.invalid", workspace, org, kind);
    }

    @Test
    void commandOnlyResourceSelectorsKeepCountsAndFieldsInsideTheirActualTargets() throws Exception {
        long target = vm(orgA, workspaceA); vm(orgB, workspaceA); vm(orgA, workspaceB);
        String period = token(Set.of(InstitutionWork.VM_PERIOD), workspaceA);
        mvc.perform(get("/api/v1/admin/vms?size=1").header("Authorization", "Bearer " + period))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(publicId("vms", target).toString()))
                .andExpect(jsonPath("$.content[0].metadataVisible").value(false))
                .andExpect(jsonPath("$.content[0].vcpu").isEmpty()).andExpect(jsonPath("$.content[0].ownerNames").isEmpty())
                .andExpect(jsonPath("$.content[0].allowedWorkIds[0]").value("vm.period"));
        long allocation = allocation(orgA, workspaceA); allocation(orgB, workspaceA);
        String gpu = token(Set.of(InstitutionWork.GPU_PERIOD), workspaceA);
        mvc.perform(get("/api/v1/admin/gpu-allocations").header("Authorization", "Bearer " + gpu))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(publicId("gpu_allocations", allocation).toString()))
                .andExpect(jsonPath("$.content[0].metadataVisible").value(false))
                .andExpect(jsonPath("$.content[0].grantedLeaseHours").value(48))
                .andExpect(jsonPath("$.content[0].ownerNames").isEmpty()).andExpect(jsonPath("$.content[0].utilizationPercent").isEmpty());
        long external = domain("EXTERNAL", null, orgA, workspaceA); domain("CUSTOM", target, orgA, workspaceA);
        String renewal = token(Set.of(InstitutionWork.DOMAIN_RENEWAL), workspaceA);
        mvc.perform(get("/api/v1/admin/domains").header("Authorization", "Bearer " + renewal))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(publicId("domains", external).toString()))
                .andExpect(jsonPath("$.content[0].metadataVisible").value(false))
                .andExpect(jsonPath("$.content[0].renewDueAt").isNotEmpty()).andExpect(jsonPath("$.content[0].dnsStatus").isEmpty());
    }

    @Test
    void workspaceInviteCommandHasOnlyItsOwnMemberReferences() throws Exception {
        String invite = token(Set.of(InstitutionWork.WORKSPACE_INVITATIONS_MANAGE), workspaceA);
        mvc.perform(get("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceA)).header("Authorization", "Bearer " + invite))
                .andExpect(status().isOk()).andExpect(jsonPath("$.metadataVisible").value(false))
                .andExpect(jsonPath("$.members.length()").value(1)).andExpect(jsonPath("$.memberCount").isEmpty())
                .andExpect(jsonPath("$.vmCount").isEmpty()).andExpect(jsonPath("$.members[0].email").isEmpty())
                .andExpect(jsonPath("$.members[0].joinedAt").isEmpty()).andExpect(jsonPath("$.members[0].supportDetailVisible").value(false));
        mvc.perform(get("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceA) + "/invitations").header("Authorization", "Bearer " + invite))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aclManageCommandCanChooseScopedMembersWithoutUserDirectoryOrMetadata() throws Exception {
        long target = vm(orgB, workspaceA);
        long system = user(UserRole.SYS_ADMIN);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'MEMBER')", workspaceA, system);
        String token = token(Set.of(InstitutionWork.VM_ACL_SUPPORT_MANAGE), workspaceA);
        mvc.perform(get("/api/v1/admin/resource-access/VM/" + publicId("vms", target)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canManageAccess").value(true))
                .andExpect(jsonPath("$.eligibleMembers.length()").value(2)).andExpect(jsonPath("$.grants.length()").value(0));
        mvc.perform(post("/api/v1/admin/resource-access/VM/" + publicId("vms", target))
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"granteeType\":\"USER\",\"userId\":\"" + publicId("users", system) + "\",\"role\":\"VIEWER\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/admin/vms/" + publicId("vms", target)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.metadataVisible").value(false)).andExpect(jsonPath("$.vcpu").isEmpty());
        mvc.perform(get("/api/v1/vms/" + publicId("vms", target)).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    @Test
    void rosterOnlyWorkspaceReferenceNeedsNoExistingInstitutionResourceOrMembership() throws Exception {
        String roster = token(Set.of(InstitutionWork.VM_ROSTER_ISSUE), workspaceB);
        mvc.perform(get("/api/v1/admin/workspaces?orgId=" + publicId("orgs", orgA)).header("Authorization", "Bearer " + roster))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(publicId("workspaces", workspaceB).toString()))
                .andExpect(jsonPath("$[0].memberCount").isEmpty()).andExpect(jsonPath("$[0].metadataVisible").value(false))
                .andExpect(jsonPath("$[0].allowedWorkIds[0]").value("vm.roster.issue"));
        mvc.perform(get("/api/v1/admin/workspaces?orgId=" + publicId("orgs", orgB)).header("Authorization", "Bearer " + roster))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceB)).header("Authorization", "Bearer " + roster))
                .andExpect(status().isOk()).andExpect(jsonPath("$.members").isEmpty());
    }

    @Test
    void rosterOnlyPersonalWorkspaceOffersActiveRecipientReferencesWithoutMemberGraph() throws Exception {
        jdbc.update("update workspaces set kind='PERSONAL' where id=?", workspaceA);
        long disabled = user(UserRole.USER);
        jdbc.update("update users set status='DISABLED' where id=?", disabled);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'MEMBER')", workspaceA, disabled);
        for (InstitutionWork work : Set.of(InstitutionWork.VM_ROSTER_ISSUE, InstitutionWork.LLM_ROSTER_ISSUE)) {
            String token = token(Set.of(work), workspaceA);
            mvc.perform(get("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceA))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.members").isEmpty())
                    .andExpect(jsonPath("$.memberCount").isEmpty()).andExpect(jsonPath("$.vmCount").isEmpty())
                    .andExpect(jsonPath("$.rosterCandidates.length()").value(1))
                    .andExpect(jsonPath("$.rosterCandidates[0].userId").value(publicId("users", requester).toString()))
                    .andExpect(jsonPath("$.rosterCandidates[0].name").isNotEmpty())
                    .andExpect(jsonPath("$.rosterCandidates[0].role").value("OWNER"))
                    .andExpect(jsonPath("$.rosterCandidates[0].status").value("ACTIVE"))
                    .andExpect(jsonPath("$.rosterCandidates[0].email").doesNotExist())
                    .andExpect(jsonPath("$.rosterCandidates[0].studentNo").doesNotExist())
                    .andExpect(jsonPath("$.rosterCandidates[0].joinedAt").doesNotExist())
                    .andExpect(jsonPath("$.rosterCandidates[0].supportDetailVisible").doesNotExist());
            mvc.perform(get("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceB))
                            .header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
        }
        String info = token(Set.of(InstitutionWork.WORKSPACE_INFO_UPDATE), workspaceA);
        mvc.perform(get("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceA))
                        .header("Authorization", "Bearer " + info))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rosterCandidates").isEmpty());
    }

    @Test
    void announcementSelectorUsesExistingServerQualifiedInstitutionAndItsOwnWorkspaceScope() throws Exception {
        long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        RequestFixtures.insertVmRequest(jdbc, workspaceA, orgA, requester, "Synthetic audience", image);
        RequestFixtures.insertLlmKeyRequest(jdbc, workspaceB, orgB, requester, "Synthetic other audience");
        String send = token(Set.of(InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND), workspaceA);
        mvc.perform(get("/api/v1/admin/workspaces").header("Authorization", "Bearer " + send))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(publicId("workspaces", workspaceA).toString()))
                .andExpect(jsonPath("$[0].allowedWorkIds[0]").value("workspace.announcement.send"))
                .andExpect(jsonPath("$[0].metadataVisible").value(false));
        mvc.perform(get("/api/v1/admin/workspaces/" + publicId("workspaces", workspaceB)).header("Authorization", "Bearer " + send)).andExpect(status().isNotFound());
    }

    @Test
    void linkedPublicationProofUsesItsOwnInstitutionAndWorkspaceInsteadOfTheVm() throws Exception {
        long target = vm(orgA, workspaceA);
        long linked = domain("CUSTOM", target, orgB, workspaceA);
        jdbc.update("update domains set verification_token='synthetic-proof' where id=?", linked);
        jdbc.update("insert into routes(domain_id,target_port,status,generation) values(?,80,'APPLIED',nextval('route_generation_seq'))", linked);
        long account = user(UserRole.ORG_VIEWER);
        grant(account, Set.of(InstitutionWork.VM_METADATA_SUPPORT_READ, InstitutionWork.VM_PUBLICATION_READ), workspaceA);
        String token = jwt.createAccessToken(users.findById(account).orElseThrow());
        mvc.perform(get("/api/v1/admin/vms/" + publicId("vms", target)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publications.length()").value(1))
                .andExpect(jsonPath("$.publications[0].domain.verification").isEmpty());
        var stored = grantSource(account, orgB, Set.of(InstitutionWork.VM_PUBLICATION_READ), workspaceA);
        mvc.perform(get("/api/v1/admin/vms/" + publicId("vms", target)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publications[0].domain.verification.token").value("synthetic-proof"));
        jdbc.update("update domains set workspace_id=? where id=?", workspaceB, linked);
        mvc.perform(get("/api/v1/admin/vms/" + publicId("vms", target)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publications.length()").value(1))
                .andExpect(jsonPath("$.publications[0].domain.verification").isEmpty());
        var replacement = InstitutionAssignment.selected(publicId("users", account), publicId("orgs", orgB),
                Set.of(InstitutionWork.VM_PUBLICATION_READ), Map.of(InstitutionWorkGroup.VM,
                        InstitutionWorkspaceScope.listed(Set.of(publicId("workspaces", workspaceB)))), 0);
        new TransactionTemplate(transactionManager).execute(status -> new InstitutionAssignmentStore(jdbc, new InstitutionAssignmentCodec()).save(replacement, stored.revision()));
        mvc.perform(get("/api/v1/admin/vms/" + publicId("vms", target)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publications[0].domain.verification.token").value("synthetic-proof"));
    }

    @Test
    void linkedGpuHardwareReferenceKeepsDetailAuthoritySeparateFromTheVm() throws Exception {
        long target = vm(orgA, workspaceA);
        long linked = allocation(orgB, workspaceB);
        jdbc.update("update gpu_allocations set vm_id=? where id=?", target, linked);
        long account = user(UserRole.ORG_VIEWER);
        var stored = grant(account, Set.of(InstitutionWork.VM_METADATA_SUPPORT_READ, InstitutionWork.GPU_METADATA_SUPPORT_READ), workspaceA);
        String token = jwt.createAccessToken(users.findById(account).orElseThrow());
        mvc.perform(get("/api/v1/admin/vms/" + publicId("vms", target)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.gpu.allocationId").value(publicId("gpu_allocations", linked).toString()))
                .andExpect(jsonPath("$.gpu.detailAccessAllowed").value(false));
        var replacement = InstitutionAssignment.selected(publicId("users", account), publicId("orgs", orgA),
                Set.of(InstitutionWork.VM_METADATA_SUPPORT_READ, InstitutionWork.GPU_METADATA_SUPPORT_READ),
                Map.of(InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(Set.of(publicId("workspaces", workspaceA))),
                        InstitutionWorkGroup.GPU, InstitutionWorkspaceScope.listed(Set.of(publicId("workspaces", workspaceB)))), 0);
        new TransactionTemplate(transactionManager).execute(status -> new InstitutionAssignmentStore(jdbc, new InstitutionAssignmentCodec()).save(replacement, stored.revision()));
        mvc.perform(get("/api/v1/admin/vms/" + publicId("vms", target)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.gpu.allocationId").value(publicId("gpu_allocations", linked).toString()))
                .andExpect(jsonPath("$.gpu.detailAccessAllowed").value(true));
    }

    @Test
    void rosterPlacementReferenceDoesNotRequireDecisionWorkOrInventAnUnscopedWorkspace() throws Exception {
        String token = token(Set.of(InstitutionWork.VM_ROSTER_ISSUE), workspaceA);
        UUID image = jdbc.queryForObject("select public_id from os_images order by id limit 1", UUID.class);
        String fields = "\"orgId\":\"" + publicId("orgs", orgA) + "\",\"imageId\":\"" + image + "\",\"vcpu\":1,\"memoryMb\":1024,\"diskGb\":10";
        mvc.perform(post("/api/v1/admin/vm-placement/preview").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{" + fields + ",\"workspaceId\":\"" + publicId("workspaces", workspaceA) + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.orgId").value(publicId("orgs", orgA).toString()));
        mvc.perform(post("/api/v1/admin/vm-placement/preview").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{" + fields + "}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void committedBulkObservationKeepsOnlyItsCommandStateWithoutMetadataRead() throws Exception {
        long target = vm(orgA, workspaceA);
        var actor = actor(Set.of(InstitutionWork.VM_PERIOD), workspaceA);
        var request = mapper.readValue("{\"targetType\":\"VM\",\"targetIds\":[\"" + publicId("vms", target)
                + "\"],\"change\":{\"kind\":\"VM_PERIOD\",\"vmPeriod\":{\"endDate\":\"2099-12-31\"}}}",
                kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest.class);
        var preview = bulk.preview(actor, request);
        var item = preview.items().getFirst();
        var apply = new kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest(request.targetType(), request.targetIds(), request.change(),
                Map.of(item.targetId(), item.fingerprint()), null, preview.previewId(), UUID.randomUUID());
        bulk.apply(actor, apply, "127.0.0.1");
        var observed = bulk.operation(actor, apply.operationId()).observations().getFirst().values();
        assertThat(observed).containsKeys("status", "startDate", "endDate", "deleteKind")
                .doesNotContainKeys("deleteRequestedAt", "deleteScheduledFor", "vcpu", "memoryMb");
    }

    @Test
    void removedRouteResendPreservesItsKnownClosedWorkspaceAndRejectsExternalKinds() throws Exception {
        long target = vm(orgA, workspaceA);
        long linked = domain("CUSTOM", target, orgA, workspaceA);
        jdbc.update("update domains set status='REMOVED' where id=?", linked);
        long route = jdbc.queryForObject("insert into routes(domain_id,target_port,status,generation) values(?,80,'REMOVED',nextval('route_generation_seq')) returning id", Long.class, linked);
        long generation = jdbc.queryForObject("select generation from routes where id=?", Long.class, route);
        String token = token(Set.of(InstitutionWork.VM_PUBLICATION_OPERATE), workspaceA);
        jdbc.update("update workspaces set deleted_at=now() where id=?", workspaceA);
        mvc.perform(get("/api/v1/admin/routes?status=REMOVED").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].metadataVisible").value(false));
        mvc.perform(post("/api/v1/admin/routes/" + publicId("routes", route) + "/apply").header("Authorization", "Bearer " + token)).andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("select generation from routes where id=?", Long.class, route)).isGreaterThan(generation);
        long external = domain("EXTERNAL", null, orgA, workspaceB);
        long malformed = jdbc.queryForObject("insert into routes(domain_id,target_port,status,generation) values(?,80,'REMOVED',nextval('route_generation_seq')) returning id", Long.class, external);
        String other = token(Set.of(InstitutionWork.VM_PUBLICATION_OPERATE), workspaceB);
        mvc.perform(post("/api/v1/admin/routes/" + publicId("routes", malformed) + "/apply").header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
    }

    @Test
    void observeOnlySelectorsCannotRecoverTheGlobalQueueCountThroughFilteredTotals() throws Exception {
        long attached = allocation(orgB, workspaceA);
        long request = jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values('GPU',?,?,?,'Synthetic queue','Synthetic queue') returning id", Long.class, workspaceA, orgB, requester);
        jdbc.update("insert into gpu_request_details(request_id,lease_hours) values(?,48)", request);
        jdbc.update("insert into gpu_allocations(request_id,workspace_id,org_id,name,granted_lease_hours) values(?,?,?,'Synthetic queued allocation',48)", request, workspaceA, orgB);
        var actor = actorAll(Set.of(InstitutionWork.GPU_OBSERVE_SUPPORT_READ));
        String token = jwt.createAccessToken(users.findById(actor.id()).orElseThrow());
        mvc.perform(get("/api/v1/admin/gpu-allocations?status=QUEUED").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/admin/gpu-allocations?workspaceId=" + publicId("workspaces", workspaceA)).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(publicId("gpu_allocations", attached).toString()))
                .andExpect(jsonPath("$.content[0].utilizationPercent").value(42.0));
        String metadata = token(Set.of(InstitutionWork.GPU_METADATA_SUPPORT_READ), workspaceA);
        mvc.perform(get("/api/v1/admin/gpu-allocations?status=QUEUED").header("Authorization", "Bearer " + metadata))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void bulkAclUsesItsTypedSupportWorkInsteadOfTheDisplayRole() throws Exception {
        long target = vm(orgB, workspaceA);
        long outside = vm(orgB, workspaceB);
        long system = user(UserRole.SYS_ADMIN);
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'MEMBER')", workspaceA, system);
        var actor = actor(Set.of(InstitutionWork.VM_ACL_SUPPORT_MANAGE), workspaceA);
        var request = mapper.readValue("{\"targetType\":\"VM\",\"targetIds\":[\"" + publicId("vms", target)
                + "\",\"" + publicId("vms", outside) + "\"],\"change\":{\"kind\":\"ACCESS\",\"access\":{\"action\":\"GRANT\",\"userId\":\""
                + publicId("users", system) + "\",\"role\":\"VIEWER\"}}}", kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest.class);
        var preview = bulk.preview(actor, request);
        assertThat(preview.items().getFirst().applicable()).isTrue();
        assertThat(preview.items().get(1).reason()).isEqualTo(kr.ac.pusan.pickle.admin.dto.AdminBulkChangeReason.NOT_FOUND);
        var apply = new kr.ac.pusan.pickle.admin.dto.AdminBulkChangeRequest(request.targetType(), request.targetIds(), request.change(),
                preview.items().stream().collect(java.util.stream.Collectors.toMap(kr.ac.pusan.pickle.admin.dto.AdminBulkChangePreviewItem::targetId,
                        kr.ac.pusan.pickle.admin.dto.AdminBulkChangePreviewItem::fingerprint)), null, preview.previewId(), UUID.randomUUID());
        bulk.apply(actor, apply, "127.0.0.1");
        assertThat(jdbc.queryForObject("select count(*) from resource_access_grants where resource_type='VM' and resource_id=? and user_id=?", Long.class, target, system)).isEqualTo(1);
    }

    private kr.ac.pusan.pickle.security.AuthenticatedUser actorAll(Set<InstitutionWork> work) {
        long account = user(work.stream().anyMatch(InstitutionWork::write) ? UserRole.ORG_MANAGER : UserRole.ORG_VIEWER);
        var stored = grant(account, work, workspaceA);
        Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes = new EnumMap<>(InstitutionWorkGroup.class);
        work.stream().map(InstitutionWork::group).filter(java.util.Objects::nonNull).forEach(group -> scopes.put(group, InstitutionWorkspaceScope.all()));
        var assignment = InstitutionAssignment.selected(publicId("users", account), publicId("orgs", orgA), work, scopes, 0);
        new TransactionTemplate(transactionManager).execute(status -> new InstitutionAssignmentStore(jdbc, new InstitutionAssignmentCodec()).save(assignment, stored.revision()));
        return actors.authenticate(publicId("users", account), 0).orElseThrow();
    }

    private kr.ac.pusan.pickle.security.AuthenticatedUser actor(Set<InstitutionWork> work, long workspace) {
        long account = user(work.stream().anyMatch(InstitutionWork::write) ? UserRole.ORG_MANAGER : UserRole.ORG_VIEWER);
        grant(account, work, workspace);
        return actors.authenticate(publicId("users", account), 0).orElseThrow();
    }

    private long allocation(long org, long workspace) {
        long targetVm = vm(org, workspace);
        long request = jdbc.queryForObject("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values('GPU',?,?,?,'Synthetic GPU','Synthetic GPU') returning id",
                Long.class, workspace, org, requester);
        jdbc.update("insert into gpu_request_details(request_id,lease_hours,vm_id) values(?,48,?)", request, targetVm);
        long node = jdbc.queryForObject("select min(id) from nodes", Long.class);
        long card = jdbc.queryForObject("insert into gpus(node_id,mapping_name,model,vram_mb,status) values(?,?,'Synthetic GPU',32768,'ACTIVE') returning id",
                Long.class, node, "synthetic-" + UUID.randomUUID());
        long allocation = jdbc.queryForObject("""
                insert into gpu_allocations(request_id,workspace_id,org_id,name,gpu_id,vm_id,status,connection_status,granted_lease_hours,allocated_at,lease_ends_at,attached_at)
                values(?,?,?,'Synthetic allocation',?,?,'ALLOCATED','ATTACHED',48,now(),now()+interval '48 hours',now()-interval '1 minute') returning id
                """, Long.class, request, workspace, org, card, targetVm);
        jdbc.update("insert into gpu_utilization_samples(allocation_id,gpu_id,sampled_at,util_percent,confidence,source) values(?,?,now(),42,1,'synthetic')", allocation, card);
        return allocation;
    }

    private String token(Set<InstitutionWork> work, long workspace) {
        UserRole role = work.stream().anyMatch(InstitutionWork::write) ? UserRole.ORG_MANAGER : UserRole.ORG_VIEWER;
        long account = user(role); grant(account, work, workspace);
        return jwt.createAccessToken(users.findById(account).orElseThrow());
    }

    private InstitutionAssignment grant(long account, Set<InstitutionWork> work, long workspace) {
        return grantSource(account, orgA, work, workspace);
    }

    private InstitutionAssignment grantSource(long account, long sourceInstitution, Set<InstitutionWork> work, long workspace) {
        UserRole role = work.stream().anyMatch(InstitutionWork::write) ? UserRole.ORG_MANAGER : UserRole.ORG_VIEWER;
        Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes = new EnumMap<>(InstitutionWorkGroup.class);
        work.stream().map(InstitutionWork::group).filter(java.util.Objects::nonNull).forEach(group ->
                scopes.put(group, InstitutionWorkspaceScope.listed(Set.of(publicId("workspaces", workspace)))));
        var selected = InstitutionAssignment.selected(publicId("users", account), publicId("orgs", sourceInstitution), work, scopes, 0);
        var codec = new InstitutionAssignmentCodec();
        var encoded = codec.encode(selected);
        long revision = jdbc.queryForObject("""
                insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision)
                values(?,?,?::user_role,?,?::jsonb,nextval('institution_permission_revision_seq'))
                returning permission_revision
                """, Long.class, account, sourceInstitution, role.name(), encoded.mode(), encoded.payload());
        return codec.decode(selected.accountId(), selected.institutionId(), role, encoded.mode(), encoded.payload(), revision);
    }

    private long user(UserRole role) {
        UUID id = UUID.randomUUID();
        return jdbc.queryForObject("insert into users(public_id,email,name,role,status) values(?,?,'Synthetic resource actor',?::user_role,'ACTIVE') returning id",
                Long.class, id, id + "@example.invalid", role.name());
    }

    private long workspace() {
        return jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT',?) returning id", Long.class, "Resource " + UUID.randomUUID());
    }

    private long vm(long org, long workspace) {
        long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        long node = jdbc.queryForObject("select min(id) from nodes", Long.class);
        long request = RequestFixtures.insertVmRequest(jdbc, workspace, org, requester, "Synthetic resource", image);
        String name = "resource-" + UUID.randomUUID();
        return jdbc.queryForObject("""
                insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)
                values(?,?,?,?,?,?,?,1,1024,10,'STOPPED') returning id
                """, Long.class, node, workspace, org, request, name, name, image);
    }

    private UUID publicId(String table, long id) {
        return jdbc.queryForObject("select public_id from " + table + " where id=?", UUID.class, id);
    }
}
