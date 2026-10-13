package kr.ac.pusan.pickle.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.orgs.OrgAdministrationLock;
import kr.ac.pusan.pickle.request.RequestService;
import kr.ac.pusan.pickle.request.dto.CreateRequestRecipient;
import kr.ac.pusan.pickle.request.dto.CreateRequestRequest;
import kr.ac.pusan.pickle.request.vm.dto.CreateVmRequestSpec;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.user.AccountService;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserPosition;
import kr.ac.pusan.pickle.user.MeController;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.user.dto.UpdateProfileRequest;
import kr.ac.pusan.pickle.workspace.dto.CreateWorkspaceRequest;
import kr.ac.pusan.pickle.workspace.dto.UpdateWorkspaceMemberRequest;
import kr.ac.pusan.pickle.workspace.dto.InviteWorkspaceMembersRequest;
import kr.ac.pusan.pickle.workspace.dto.InviteWorkspaceMembersRequest.WorkspaceInvitationEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class WorkspaceOwnershipSerializationTest {
    private static final String PASSWORD = "Synthetic-owner-271!";
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceMemberRepository members;
    @Autowired private WorkspaceService service;
    @Autowired private AccountService accounts;
    @Autowired private PersonalWorkspaceService personalWorkspaces;
    @Autowired private PasswordEncoder passwords;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private OrgAdministrationLock administrationLock;
    @Autowired private WorkspaceInvitationService invitations;
    @Autowired private MeController me;
    @Autowired private RequestService requests;

    @Test
    void overlappingSelfDemotionsPreserveOneOwner() throws Exception {
        User first = user();
        User second = user();
        Workspace workspace = workspace(WorkspaceKind.PROJECT, first, second);
        var outcomes = overlap(
                () -> service.updateMemberRole(actor(first), workspace.getPublicId(), first.getPublicId(),
                        new UpdateWorkspaceMemberRequest(WorkspaceMemberRole.MEMBER), "127.0.0.31"),
                () -> service.updateMemberRole(actor(second), workspace.getPublicId(), second.getPublicId(),
                        new UpdateWorkspaceMemberRequest(WorkspaceMemberRole.MEMBER), "127.0.0.32"));
        assertThat(outcomes.first()).isNull();
        assertBusinessError(outcomes.second(), "WORKSPACE_SOLE_OWNER_REMOVAL");
        assertThat(outcomes.waited()).as("second demotion waited on the common administration lock").isTrue();
        assertThat(ownerCount(workspace)).isEqualTo(1);
        assertThat(auditCount(workspace, "workspace.member_update")).isEqualTo(1);
    }

    @Test
    void overlappingSelfLeavesPreserveOneOwner() throws Exception {
        User first = user();
        User second = user();
        Workspace workspace = workspace(WorkspaceKind.PROJECT, first, second);
        var outcomes = overlap(
                () -> service.removeMember(actor(first), workspace.getPublicId(), first.getPublicId(), "127.0.0.33"),
                () -> service.removeMember(actor(second), workspace.getPublicId(), second.getPublicId(), "127.0.0.34"));
        assertThat(outcomes.first()).isNull();
        assertBusinessError(outcomes.second(), "WORKSPACE_SOLE_OWNER_REMOVAL");
        assertThat(outcomes.waited()).as("second leave waited on the common administration lock").isTrue();
        assertThat(ownerCount(workspace)).isEqualTo(1);
        assertThat(auditCount(workspace, "workspace.member_remove")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = WorkspaceKind.class, names = "PERSONAL", mode = EnumSource.Mode.EXCLUDE)
    void lastOwnerCannotWithdrawFromAnEmptyWorkspace(WorkspaceKind kind) {
        User owner = user();
        Workspace workspace = workspace(kind, owner);
        assertThatThrownBy(() -> withdraw(owner))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo("ACCOUNT_SOLE_OWNER_OF_ACTIVE_WORKSPACE"));
        assertThat(status(owner)).isEqualTo("ACTIVE");
        assertThat(ownerCount(workspace)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(ResourceType.class)
    void lastOwnerWithdrawalPreservesEveryLiveResourceFamily(ResourceType type) {
        User owner = user();
        Workspace workspace = workspace(WorkspaceKind.PROJECT, owner);
        String table = insertResource(type, workspace, owner);
        assertThatThrownBy(() -> withdraw(owner)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).getCode())
                        .isEqualTo("ACCOUNT_SOLE_OWNER_OF_ACTIVE_WORKSPACE"));
        assertThat(status(owner)).isEqualTo("ACTIVE");
        assertThat(ownerCount(workspace)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from " + table + " where workspace_id = ?",
                Integer.class, workspace.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from user_status_changes where user_id = ?",
                Integer.class, owner.getId())).isZero();
    }

    @Test
    void withdrawalRollsBackEveryMembershipWhenAnyWorkspaceWouldLoseItsOwner() {
        User owner = user();
        User peer = user();
        Workspace shared = workspace(WorkspaceKind.COURSE, owner, peer);
        Workspace sole = workspace(WorkspaceKind.LAB, owner);
        personalWorkspaces.ensurePersonalWorkspace(owner);
        assertThatThrownBy(() -> withdraw(owner)).isInstanceOf(ApiException.class);
        assertThat(status(owner)).isEqualTo("ACTIVE");
        assertThat(ownerCount(shared)).isEqualTo(2);
        assertThat(ownerCount(sole)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from workspace_members where user_id = ?",
                Integer.class, owner.getId())).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from user_status_changes where user_id = ?",
                Integer.class, owner.getId())).isZero();
    }

    @Test
    void deletedWorkspacesDoNotBlockWithdrawalAndPersonalWorkspaceIsCleanedUp() {
        User owner = user();
        Workspace deleted = workspace(WorkspaceKind.PROJECT, owner);
        jdbc.update("update workspaces set deleted_at = now(), deleted_by = ? where id = ?", owner.getId(), deleted.getId());
        personalWorkspaces.ensurePersonalWorkspace(owner);
        withdraw(owner);
        assertThat(status(owner)).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("select count(*) from workspace_members where user_id = ?",
                Integer.class, owner.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from workspaces where kind = 'PERSONAL' and deleted_by = ? and deleted_at is not null",
                Integer.class, owner.getId())).isEqualTo(1);
    }

    @Test
    void withdrawalFromSeveralSharedWorkspacesPreservesTheirRemainingOwners() {
        User departing = user();
        User remaining = user();
        Workspace first = workspace(WorkspaceKind.COURSE, departing, remaining);
        Workspace second = workspace(WorkspaceKind.LAB, departing, remaining);
        personalWorkspaces.ensurePersonalWorkspace(departing);
        withdraw(departing);
        assertThat(status(departing)).isEqualTo("WITHDRAWN");
        assertThat(ownerCount(first)).isEqualTo(1);
        assertThat(ownerCount(second)).isEqualTo(1);
        assertThat(memberCount(first, remaining)).isEqualTo(1);
        assertThat(memberCount(second, remaining)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from workspace_members where user_id = ?",
                Integer.class, departing.getId())).isZero();
    }

    @Test
    void committedWorkspaceCreationIsSeenByAnOverlappingWithdrawal() throws Exception {
        User owner = user();
        var outcomes = overlap(
                () -> service.create(actor(owner), new CreateWorkspaceRequest(CreatableWorkspaceKind.PROJECT,
                        "합성 초기 소유자", null), "127.0.0.35"),
                () -> withdraw(owner));
        assertThat(outcomes.first()).isNull();
        assertBusinessError(outcomes.second(), "ACCOUNT_SOLE_OWNER_OF_ACTIVE_WORKSPACE");
        assertThat(outcomes.waited()).isTrue();
        assertThat(status(owner)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from workspace_members where user_id = ? and role = 'OWNER'",
                Integer.class, owner.getId())).isEqualTo(1);
    }

    @Test
    void workspaceCreationCannotAppointAnOwnerWhoJustWithdrew() throws Exception {
        User owner = user();
        var outcomes = overlap(() -> withdraw(owner),
                () -> service.create(actor(owner), new CreateWorkspaceRequest(CreatableWorkspaceKind.PROJECT,
                        "합성 탈퇴 경합", null), "127.0.0.36"));
        assertThat(outcomes.first()).isNull();
        assertThat(outcomes.second()).isInstanceOf(ApiException.class);
        assertThat(outcomes.waited()).isTrue();
        assertThat(status(owner)).isEqualTo("WITHDRAWN");
        assertThat(jdbc.queryForObject("select count(*) from workspace_members where user_id = ?",
                Integer.class, owner.getId())).isZero();
    }

    @Test
    void userDemotionAndSupportRemovalShareTheSameOwnerGuard() throws Exception {
        User first = user();
        User second = user();
        User administrator = administrator();
        Workspace workspace = workspace(WorkspaceKind.CLUB, first, second);
        var outcomes = overlap(
                () -> service.updateMemberRole(actor(first), workspace.getPublicId(), first.getPublicId(),
                        new UpdateWorkspaceMemberRequest(WorkspaceMemberRole.MEMBER), "127.0.0.37"),
                () -> service.removeMember(actor(administrator), workspace.getPublicId(), second.getPublicId(),
                        "127.0.0.38", true));
        assertThat(outcomes.first()).isNull();
        assertBusinessError(outcomes.second(), "WORKSPACE_SOLE_OWNER_REMOVAL");
        assertThat(outcomes.waited()).isTrue();
        assertThat(ownerCount(workspace)).isEqualTo(1);
        assertThat(auditCount(workspace, "workspace.member_update")).isEqualTo(1);
        assertThat(auditCount(workspace, "workspace.member_remove")).isZero();
    }

    @Test
    void supportDemotionCommitsBeforeWithdrawalRechecksEveryWorkspace() throws Exception {
        User first = user();
        User second = user();
        User administrator = administrator();
        Workspace shared = workspace(WorkspaceKind.LAB, first, second);
        Workspace other = workspace(WorkspaceKind.STUDY, second, administrator);
        var outcomes = overlap(
                () -> service.updateMemberRole(actor(administrator), shared.getPublicId(), first.getPublicId(),
                        new UpdateWorkspaceMemberRequest(WorkspaceMemberRole.MEMBER), "127.0.0.39", true),
                () -> withdraw(second));
        assertThat(outcomes.first()).isNull();
        assertBusinessError(outcomes.second(), "ACCOUNT_SOLE_OWNER_OF_ACTIVE_WORKSPACE");
        assertThat(outcomes.waited()).isTrue();
        assertThat(status(second)).isEqualTo("ACTIVE");
        assertThat(ownerCount(shared)).isEqualTo(1);
        assertThat(ownerCount(other)).isEqualTo(2);
    }

    @Test
    void invitationTakesAdministrationLockBeforeTheInviteeIdentifier() throws Exception {
        User owner = user();
        User invitee = user();
        Workspace workspace = workspace(WorkspaceKind.PROJECT, owner);
        underHeldAdministrationAndIdentifier(InvitationLocks.emailKey(invitee.getEmail()),
                () -> invitations.invite(actor(owner), workspace.getPublicId(), new InviteWorkspaceMembersRequest(
                        List.of(new WorkspaceInvitationEntry(invitee.getEmail(), null))), "127.0.0.40"),
                () -> assertThat(memberCount(workspace, invitee)).isZero());
        assertThat(memberCount(workspace, invitee)).isEqualTo(1);
    }

    @Test
    void profileClaimTakesAdministrationLockBeforeEmailAndStudentNumber() throws Exception {
        User owner = user();
        User invitee = user();
        Workspace workspace = workspace(WorkspaceKind.COURSE, owner);
        String studentNo = "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long invitationId = jdbc.queryForObject("""
                insert into workspace_invitations (workspace_id, invitee_student_no, role, invited_by)
                values (?, ?, 'MEMBER', ?) returning id
                """, Long.class, workspace.getId(), studentNo, owner.getId());
        var profile = new UpdateProfileRequest();
        profile.setPosition(UserPosition.STUDENT_UNDERGRAD);
        profile.setStudentNo(studentNo);
        underHeldAdministrationAndIdentifier(InvitationLocks.emailKey(invitee.getEmail()),
                () -> me.updateMyProfile(actor(invitee), profile, new MockHttpServletRequest()),
                () -> {
                    assertThat(memberCount(workspace, invitee)).isZero();
                    assertThat(jdbc.queryForObject("select student_no from users where id = ?", String.class, invitee.getId())).isNull();
                });
        assertThat(memberCount(workspace, invitee)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from workspace_invitations where id = ?", String.class, invitationId))
                .isEqualTo("ACCEPTED");
    }

    @Test
    void recipientRequestTakesAdministrationLockBeforeStudentNumberPlacement() throws Exception {
        User owner = user();
        User invitee = user();
        Workspace workspace = workspace(WorkspaceKind.PROJECT, owner);
        String studentNo = "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        jdbc.update("update users set position = 'STUDENT_UNDERGRAD', student_no = ? where id = ?", studentNo, invitee.getId());
        UUID orgId = SeedFixtures.publicId(jdbc, "orgs", SeedFixtures.seedOrgId(jdbc));
        UUID imageId = jdbc.queryForObject("select public_id from os_images where status = 'ACTIVE' order by id limit 1", UUID.class);
        var form = new CreateRequestRequest(ResourceType.VM, workspace.getPublicId(), orgId,
                "합성 수신자 신청", null, null, LocalDate.now().plusMonths(4), null, "합성 VM",
                new CreateVmRequestSpec(imageId, null, 1, 1024, 32, null, null), null, null, null,
                List.of(new CreateRequestRecipient(null, null, studentNo)), null);
        underHeldAdministrationAndIdentifier(InvitationLocks.studentNoKey(studentNo),
                () -> requests.create(actor(owner), form, "127.0.0.41"),
                () -> {
                    assertThat(memberCount(workspace, invitee)).isZero();
                    assertThat(jdbc.queryForObject("select count(*) from requests where workspace_id = ?", Integer.class, workspace.getId())).isZero();
                });
        assertThat(memberCount(workspace, invitee)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from requests where workspace_id = ?", Integer.class, workspace.getId())).isEqualTo(1);
    }

    /** Holds both locks so a waiter identifies which one the real service asks for first. */
    private void underHeldAdministrationAndIdentifier(String identifier, Runnable action, Runnable beforeRelease) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        var pid = new AtomicInteger();
        var threads = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> holder = threads.submit(() -> outcome(() -> transaction(() -> {
                administrationLock.acquire();
                jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))", "workspace_invitation|" + identifier);
                held.countDown();
                await(release);
            })));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Throwable> mutation = threads.submit(() -> outcome(() -> transaction(() -> {
                pid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                started.countDown();
                action.run();
            })));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(waitUntilBlockedOrFinished(pid.get(), mutation)).as("service waits on administration before identifier lock").isTrue();
            beforeRelease.run();
            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS)).isNull();
            assertThat(mutation.get(10, TimeUnit.SECONDS)).isNull();
        } finally {
            release.countDown();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    /** The first mutation has run but is uncommitted when the second starts. */
    private Outcomes overlap(Runnable firstAction, Runnable secondAction) throws Exception {
        var firstReady = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondReady = new CountDownLatch(1);
        var secondPid = new AtomicInteger();
        var threads = Executors.newFixedThreadPool(2);
        try {
            Future<Throwable> first = threads.submit(() -> outcome(() -> transaction(() -> {
                firstAction.run();
                firstReady.countDown();
                await(releaseFirst);
            })));
            if (!firstReady.await(10, TimeUnit.SECONDS)) {
                if (first.isDone()) throw new AssertionError("first mutation failed before its commit barrier", first.get());
                throw new AssertionError("first mutation did not reach its commit barrier");
            }
            Future<Throwable> second = threads.submit(() -> outcome(() -> transaction(() -> {
                secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                secondReady.countDown();
                secondAction.run();
            })));
            assertThat(secondReady.await(5, TimeUnit.SECONDS)).isTrue();
            boolean waited = waitUntilBlockedOrFinished(secondPid.get(), second);
            releaseFirst.countDown();
            return new Outcomes(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS), waited);
        } finally {
            releaseFirst.countDown();
            threads.shutdownNow();
            assertThat(threads.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private boolean waitUntilBlockedOrFinished(int pid, Future<?> mutation) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (mutation.isDone()) return false;
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from pg_locks where pid = ? and locktype = 'advisory'
                     and classid = 1129074511 and objid = 1 and not granted)
                    """, Boolean.class, pid))) return true;
            if (Boolean.TRUE.equals(jdbc.queryForObject("select cardinality(pg_blocking_pids(?)) > 0", Boolean.class, pid))) return false;
            Thread.sleep(10);
        }
        throw new AssertionError("second mutation neither waited on a PostgreSQL lock nor finished");
    }

    private void transaction(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.execute("set local statement_timeout = '8s'");
            action.run();
        });
    }

    private static Throwable outcome(Runnable action) {
        try { action.run(); return null; }
        catch (Throwable error) { return error; }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("commit barrier timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private User user() {
        User user = new User("ownership." + UUID.randomUUID() + "@pusan.ac.kr", passwords.encode(PASSWORD), "합성 소유자");
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        return users.saveAndFlush(user);
    }

    private User administrator() {
        User user = user();
        user.setRole(UserRole.SYS_ADMIN);
        return users.saveAndFlush(user);
    }

    private Workspace workspace(WorkspaceKind kind, User... owners) {
        Workspace workspace = workspaces.saveAndFlush(new Workspace(kind, "합성 ownership " + UUID.randomUUID(), null));
        for (User owner : owners) members.saveAndFlush(new WorkspaceMember(workspace, owner.getId(), WorkspaceMemberRole.OWNER));
        return workspace;
    }

    private String insertResource(ResourceType type, Workspace workspace, User owner) {
        long org = SeedFixtures.seedOrgId(jdbc);
        long workspaceId = workspace.getId();
        return switch (type) {
            case VM -> {
                long request = RequestFixtures.insertVmRequest(jdbc, workspaceId, org, owner.getId(), "합성 탈퇴 VM", null);
                long node = jdbc.queryForObject("select min(id) from nodes", Long.class);
                long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
                String hostname = "ownership-" + UUID.randomUUID();
                jdbc.update("""
                        insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)
                        values(?,?,?,?,?,?,?,2,2048,10,'RUNNING')
                        """, node, workspaceId, org, request, hostname, hostname, image);
                yield "vms";
            }
            case LLM_API_KEY -> {
                long request = RequestFixtures.insertLlmKeyRequest(jdbc, workspaceId, org, owner.getId(), "합성 탈퇴 LLM");
                jdbc.update("""
                        insert into llm_api_keys(workspace_id,org_id,request_id,name,created_by,status)
                        values(?,?,?,'합성 탈퇴 키',?,'ACTIVE')
                        """, workspaceId, org, request, owner.getId());
                yield "llm_api_keys";
            }
            case DOMAIN -> {
                jdbc.update("""
                        insert into domains(workspace_id,org_id,kind,fqdn,root_domain,status,renew_due_at)
                        values(?,?,'EXTERNAL',?,'pusan.dev','ACTIVE',now()+interval '30 days')
                        """, workspaceId, org, "ownership-" + UUID.randomUUID() + ".pusan.dev");
                yield "domains";
            }
            case GPU -> {
                long request = jdbc.queryForObject("""
                        insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name)
                        values('GPU',?,?,?,'합성 탈퇴 GPU','합성 탈퇴 GPU') returning id
                        """, Long.class, workspaceId, org, owner.getId());
                jdbc.update("insert into gpu_request_details(request_id,lease_hours) values(?,1)", request);
                jdbc.update("""
                        insert into gpu_allocations(request_id,workspace_id,org_id,name,granted_lease_hours)
                        values(?,?,?,'합성 탈퇴 GPU',1)
                        """, request, workspaceId, org);
                yield "gpu_allocations";
            }
        };
    }

    private AuthenticatedUser actor(User user) {
        return new AuthenticatedUser(user.getId(), user.getPublicId(), user.getEmail(), user.getRole(), Map.of());
    }

    private void withdraw(User user) {
        accounts.withdraw(user.getId(), PASSWORD, null, null, "127.0.1." + (user.getId() % 200 + 1));
    }

    private int ownerCount(Workspace workspace) {
        return jdbc.queryForObject("select count(*) from workspace_members where workspace_id = ? and role = 'OWNER'",
                Integer.class, workspace.getId());
    }

    private int memberCount(Workspace workspace, User user) {
        return jdbc.queryForObject("select count(*) from workspace_members where workspace_id = ? and user_id = ?",
                Integer.class, workspace.getId(), user.getId());
    }

    private int auditCount(Workspace workspace, String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where target_id = ? and action = ?",
                Integer.class, workspace.getPublicId().toString(), action);
    }

    private String status(User user) {
        return jdbc.queryForObject("select status::text from users where id = ?", String.class, user.getId());
    }

    private static void assertBusinessError(Throwable error, String code) {
        assertThat(error).isInstanceOf(ApiException.class);
        assertThat(((ApiException) error).getCode()).isEqualTo(code);
    }

    private record Outcomes(Throwable first, Throwable second, boolean waited) { }
}
