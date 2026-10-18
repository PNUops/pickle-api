package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.access.ResourceType;
import kr.ac.pusan.pickle.admin.dto.SaveOrgRequestMailSettingsRequest;
import kr.ac.pusan.pickle.admin.dto.SaveOrgOperationsRequest;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgAdministrationLock;
import kr.ac.pusan.pickle.orgs.OrgAccountStatusChanges;
import kr.ac.pusan.pickle.orgs.RequestMailMode;
import kr.ac.pusan.pickle.security.AuthenticatedUser;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.security.institution.InstitutionActorProvider;
import kr.ac.pusan.pickle.security.institution.InstitutionActorContext;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionAuthority;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkTarget;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionPermissionInput;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionPermissionMode;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionWorkspaceScopeInput;
import kr.ac.pusan.pickle.security.institution.dto.InstitutionWorkspaceScopeMode;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InstitutionAdministrationTest {
    @Autowired private AdminOrgOperationsService operations;
    @Autowired private AdminOrgOperationsQueryService query;
    @Autowired private AdminUserService adminUsers;
    @Autowired private InstitutionActorProvider actors;
    @Autowired private OrgRepository orgs;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private OrgAdministrationLock administrationLock;
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private OrgAccountStatusChanges accountStatusChanges;
    @MockitoSpyBean private AuditService audit;

    private Org org;
    private User admin;
    private User sys;
    private User subject;

    @BeforeEach
    void setUp() {
        org = orgs.saveAndFlush(new Org("명시 권한 원자 검증 " + UUID.randomUUID(), null));
        admin = user(UserRole.ORG_ADMIN);
        sys = user(UserRole.SYS_ADMIN);
        subject = user(UserRole.USER);
        SeedFixtures.grantOrgRole(jdbc, admin.getId(), org.getId(), UserRole.ORG_ADMIN);
    }

    @Test
    void pendingCallerStatusChangeIsFlushedBeforeFreshRosterAdmission() {
        var original = principal(sys);
        var request = unchangedRoster();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            users.findById(sys.getId()).orElseThrow().setStatus(UserStatus.DISABLED);
            operations.save(original, org.getPublicId(), request, "127.0.0.1");
        })).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(orgRevision()).isZero();
    }

    @Test
    void pendingCallerTokenRevocationIsFlushedBeforeFreshSingleAdmission() {
        var original = principal(sys);
        var permission = full(sourceRevision(admin));
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            users.findById(sys.getId()).orElseThrow().bumpTokenVersion();
            operations.saveSingle(original, org.getPublicId(), admin.getPublicId(), permission,
                    false, 0, permission.expectedRevision(), "127.0.0.1");
        })).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(orgRevision()).isZero();
    }

    @Test
    void pendingCallerStatusChangeIsFlushedBeforeReceiptOnlyAdmission() {
        var original = principal(sys);
        var request = new SaveOrgRequestMailSettingsRequest(0L, RequestMailMode.DESIGNATED,
                Set.of(admin.getPublicId()), null);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            users.findById(sys.getId()).orElseThrow().setStatus(UserStatus.DISABLED);
            operations.saveMail(original, org.getPublicId(), request, "127.0.0.1");
        })).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(orgRevision()).isZero();
    }

    @Test
    void pendingCallerTokenRevocationIsFlushedBeforeSingleReceiptAdmission() {
        var original = principal(sys);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            users.findById(sys.getId()).orElseThrow().bumpTokenVersion();
            operations.saveSingleMail(original, org.getPublicId(), admin.getPublicId(), true,
                    0, "127.0.0.1");
        })).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(orgRevision()).isZero();
    }

    @Test
    void selectingEverySelectableWorkNeverCreatesFullAuthority() {
        var all = Arrays.stream(InstitutionWork.values()).filter(InstitutionWork::selectable).toList();
        change(subject, selected(0, all));
        var authority = authority(subject);
        assertThat(authority.assignments()).singleElement()
                .satisfies(source -> assertThat(source.mode()).isEqualTo(InstitutionAssignment.Mode.SELECTED));
        assertThat(authority.displayRole()).contains(UserRole.ORG_MANAGER);
        assertThat(authority.evaluate(InstitutionWork.INSTITUTION_DELEGATE,
                InstitutionWorkTarget.institution(org.getPublicId())).allowed()).isFalse();
        assertThat(authority.evaluate(InstitutionWork.LLM_KEY_COST_READ,
                InstitutionWorkTarget.resource(UUID.randomUUID(), InstitutionWorkGroup.LLM, UUID.randomUUID())).allowed()).isFalse();
        assertThat(query.get(principal(sys), org.getPublicId(), null).activeAdminCount()).isEqualTo(1);
    }

    @Test
    void sameRoleWorkEditChangesSourceAndFingerprintWithoutRevokingPersonalToken() {
        change(subject, selected(0, List.of(InstitutionWork.VM_METADATA_READ)));
        var before = authority(subject);
        long source = sourceRevision(subject);
        long accountRevision = accountRevision(subject);
        change(subject, selected(source, List.of(InstitutionWork.VM_METADATA_READ, InstitutionWork.LLM_METADATA_READ)));
        var after = authority(subject);
        assertThat(sourceRevision(subject)).isGreaterThan(source);
        assertThat(after.fingerprint()).isNotEqualTo(before.fingerprint());
        assertThat(after.displayRole()).isEqualTo(before.displayRole());
        assertThat(accountRevision(subject)).isEqualTo(accountRevision);
        assertThat(users.findById(subject.getId()).orElseThrow().getTokenVersion()).isZero();
        assertThat(actors.authenticate(subject.getPublicId(), 0)).isPresent();
    }

    @Test
    void unchangedAuthorityKeepsItsSourceRevisionWhileRosterEpochAdvances() {
        change(subject, selected(0, List.of(InstitutionWork.VM_METADATA_READ)));
        long source = sourceRevision(subject);
        long epoch = orgRevision();
        String fingerprint = authority(subject).fingerprint();
        change(subject, selected(source, List.of(InstitutionWork.VM_METADATA_READ)));
        assertThat(sourceRevision(subject)).isEqualTo(source);
        assertThat(orgRevision()).isEqualTo(epoch + 1);
        assertThat(authority(subject).fingerprint()).isEqualTo(fingerprint);
    }

    @Test
    void revokeAndRecreateNeverReuseSourceRevisionAndPreserveThePersonalSession() {
        change(subject, selected(0, List.of(InstitutionWork.VM_METADATA_READ)));
        long removedSource = sourceRevision(subject);
        operations.saveSingle(principal(sys), org.getPublicId(), subject.getPublicId(), null,
                true, orgRevision(), removedSource, "127.0.0.1");
        var personal = actors.authenticate(subject.getPublicId(), 0).orElseThrow();
        assertThat(personal.role()).isEqualTo(UserRole.USER);
        assertThat(personal.institutionContext().state()).isEqualTo(InstitutionActorContext.State.VALID_EMPTY);
        change(subject, selected(0, List.of(InstitutionWork.VM_METADATA_READ)));
        long replacement = sourceRevision(subject);
        assertThat(replacement).isGreaterThan(removedSource);
        expectStatus(HttpStatus.CONFLICT, () -> change(subject,
                selected(removedSource, List.of(InstitutionWork.LLM_METADATA_READ))));
        assertThat(sourceRevision(subject)).isEqualTo(replacement);
    }

    @Test
    void freshOrgRevisionCannotSubstituteForAStaleSourceRevision() {
        change(subject, selected(0, List.of(InstitutionWork.VM_METADATA_READ)));
        long staleSource = sourceRevision(subject);
        change(subject, selected(staleSource, List.of(InstitutionWork.LLM_METADATA_READ)));
        var before = storedPermissions();
        expectStatus(HttpStatus.CONFLICT, () -> change(subject,
                selected(staleSource, List.of(InstitutionWork.VM_METADATA_READ))));
        assertThat(storedPermissions()).isEqualTo(before);
    }

    @Test
    void freshSourceRevisionCannotSubstituteForAStaleOrgRevision() {
        change(subject, selected(0, List.of(InstitutionWork.VM_METADATA_READ)));
        var before = storedPermissions();
        var permission = selected(sourceRevision(subject), List.of(InstitutionWork.LLM_METADATA_READ));
        expectStatus(HttpStatus.CONFLICT, () -> operations.saveSingle(principal(sys), org.getPublicId(), subject.getPublicId(),
                permission, false, orgRevision() - 1, permission.expectedRevision(), "127.0.0.1"));
        assertThat(storedPermissions()).isEqualTo(before);
    }

    @Test
    void wholeRosterRequiresTheExactCompleteSourceRevisionMap() {
        var before = unchangedRoster();
        var omitted = new SaveOrgOperationsRequest(0L, Map.of(), before.members(),
                RequestMailMode.DESIGNATED, null, false, null);
        expectStatus(HttpStatus.CONFLICT, () -> operations.save(principal(sys), org.getPublicId(), omitted, "127.0.0.1"));
        var extra = new LinkedHashMap<>(before.expectedPermissionRevisions());
        extra.put(UUID.randomUUID(), 1L);
        var injected = new SaveOrgOperationsRequest(0L, extra, before.members(), RequestMailMode.DESIGNATED, null, false, null);
        expectStatus(HttpStatus.CONFLICT, () -> operations.save(principal(sys), org.getPublicId(), injected, "127.0.0.1"));
        assertThat(orgRevision()).isZero();
    }

    @Test
    void receiptOnlyWriterCannotChangeAuthorityOrEnqueueNotifications() {
        change(subject, selected(0, List.of(InstitutionWork.INSTITUTION_REQUEST_MAIL_CONFIGURE,
                InstitutionWork.INSTITUTION_REQUEST_MAIL_READ)));
        var actor = principal(subject);
        var before = storedPermissions();
        long accountRevision = accountRevision(subject);
        long pending = jdbc.queryForObject("select count(*) from notifications", Long.class);
        var result = operations.saveMail(actor, org.getPublicId(),
                new SaveOrgRequestMailSettingsRequest(orgRevision(), RequestMailMode.DESIGNATED,
                        Set.of(admin.getPublicId()), "수신 설정만 변경"), "127.0.0.1");
        assertThat(storedPermissions()).isEqualTo(before);
        assertThat(accountRevision(subject)).isEqualTo(accountRevision);
        assertThat(users.findById(subject.getId()).orElseThrow().getTokenVersion()).isZero();
        assertThat(result.members()).allSatisfy(member -> assertThat(member.permission()).isNull());
        assertThat(result.currentMailRecipientCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notifications", Long.class)).isEqualTo(pending);
        expectStatus(HttpStatus.NOT_FOUND, () -> operations.saveSingle(actor, org.getPublicId(), admin.getPublicId(),
                full(sourceRevision(admin)), false, orgRevision(), sourceRevision(admin), "127.0.0.1"));
    }

    @Test
    void emptyListedDecisionScopeIsRetainedWithoutBecomingAnActualDecider() {
        change(subject, listed(0, InstitutionWork.VM_REQUEST_DECIDE, List.of()));
        var source = authority(subject).assignments().getFirst();
        assertThat(source.scopes().get(InstitutionWorkGroup.VM).workspaceIds()).isEmpty();
        assertThat(query.get(principal(sys), org.getPublicId(), null).activeApproverCount()).isEqualTo(1);
        assertThat(authority(subject).evaluate(InstitutionWork.VM_REQUEST_DECIDE,
                InstitutionWorkTarget.resource(org.getPublicId(), InstitutionWorkGroup.VM, UUID.randomUUID())).allowed()).isFalse();
    }

    @Test
    void newlySelectedMissingOrDeletedWorkspaceIsRejectedWithoutWidening() {
        UUID deleted = workspace();
        jdbc.update("update workspaces set deleted_at=now() where public_id=?", deleted);
        expectStatus(HttpStatus.UNPROCESSABLE_CONTENT, () -> change(subject,
                listed(0, InstitutionWork.VM_METADATA_READ, List.of(deleted))));
        expectStatus(HttpStatus.UNPROCESSABLE_CONTENT, () -> change(subject,
                listed(0, InstitutionWork.VM_METADATA_READ, List.of(UUID.randomUUID()))));
        assertThat(actors.authenticate(subject.getPublicId(), 0).orElseThrow().institutionContext().state())
                .isEqualTo(InstitutionActorContext.State.VALID_EMPTY);
        assertThat(orgRevision()).isZero();
    }

    @Test
    void retainedDeletedWorkspaceStaysListedAndDoesNotAdmitANewWorkspace() {
        UUID deleted = workspace();
        change(subject, listed(0, InstitutionWork.VM_METADATA_READ, List.of(deleted)));
        jdbc.update("update workspaces set deleted_at=now() where public_id=?", deleted);
        change(subject, listed(sourceRevision(subject), InstitutionWork.VM_OBSERVE, List.of(deleted)));
        var scope = authority(subject).assignments().getFirst().scopes().get(InstitutionWorkGroup.VM);
        assertThat(scope.mode().name()).isEqualTo("LISTED");
        assertThat(scope.workspaceIds()).containsExactly(deleted);
        UUID freshWorkspace = workspace();
        assertThat(authority(subject).evaluate(InstitutionWork.VM_OBSERVE,
                InstitutionWorkTarget.resource(org.getPublicId(), InstitutionWorkGroup.VM, freshWorkspace)).allowed()).isFalse();
    }

    @Test
    void invalidWholeAccountAuthorityCannotBeSilentlyRepairedByEditingOneRow() {
        change(subject, selected(0, List.of(InstitutionWork.VM_METADATA_READ)));
        jdbc.update("update users set role='USER' where id=?", subject.getId());
        var broken = actors.authenticate(subject.getPublicId(), 0).orElseThrow();
        assertThat(broken.institutionContext().state()).isEqualTo(InstitutionActorContext.State.INVALID);
        var before = storedPermissions();
        expectStatus(HttpStatus.CONFLICT, () -> change(subject,
                selected(sourceRevision(subject), List.of(InstitutionWork.LLM_METADATA_READ))));
        assertThat(storedPermissions()).isEqualTo(before);
    }

    @Test
    void orgDelegateCannotEditItsOwnAuthorityAndSystemAccountsCannotReceiveInstitutionSources() {
        var before = unchangedRoster();
        var changed = new SaveOrgOperationsRequest(before.expectedRevision(), before.expectedPermissionRevisions(),
                List.of(new SaveOrgOperationsRequest.Assignment(admin.getPublicId(),
                        selected(sourceRevision(admin), List.of(InstitutionWork.VM_METADATA_READ)), false)),
                RequestMailMode.DESIGNATED, null, false, null);
        expectStatus(HttpStatus.FORBIDDEN, () -> operations.save(principal(admin), org.getPublicId(), changed, "127.0.0.1"));
        User otherSys = user(UserRole.SYS_VIEWER);
        expectStatus(HttpStatus.FORBIDDEN, () -> change(otherSys, full(0)));
        expectStatus(HttpStatus.FORBIDDEN, () -> change(sys, full(0)));
        assertThat(orgRevision()).isZero();
    }

    @Test
    void anotherFullSourcePreservesGlobalKeyFinanceAndIsExplainedBeforeLosingThisSource() {
        change(subject, full(0));
        Org other = orgs.saveAndFlush(new Org("다른 FULL " + UUID.randomUUID(), null));
        SeedFixtures.grantOrgRole(jdbc, subject.getId(), other.getId(), UserRole.ORG_ADMIN);
        var request = rosterWithSubject(selected(sourceRevision(subject), List.of(InstitutionWork.VM_METADATA_READ)));
        assertThat(operations.preview(principal(sys), org.getPublicId(), request).warnings())
                .anySatisfy(warning -> assertThat(warning).contains("다른 FULL 출처", "유지"));
        operations.save(principal(sys), org.getPublicId(), request, "127.0.0.1");
        assertThat(authority(subject).evaluate(InstitutionWork.LLM_KEY_COST_READ,
                InstitutionWorkTarget.resource(UUID.randomUUID(), InstitutionWorkGroup.LLM, UUID.randomUUID())).allowed()).isTrue();
        assertThat(users.findById(subject.getId()).orElseThrow().getRole()).isEqualTo(UserRole.ORG_ADMIN);
    }

    @Test
    void lastFullSourceWarningDistinguishesTheLossOfGlobalKeyFinance() {
        change(subject, full(0));
        var request = rosterWithSubject(selected(sourceRevision(subject), List.of(InstitutionWork.VM_METADATA_READ)));
        assertThat(operations.preview(principal(sys), org.getPublicId(), request).warnings())
                .anySatisfy(warning -> assertThat(warning).contains("마지막 FULL 출처", "비용 읽기도 없어집니다"));
        operations.save(principal(sys), org.getPublicId(), request, "127.0.0.1");
        assertThat(authority(subject).evaluate(InstitutionWork.LLM_KEY_COST_READ,
                InstitutionWorkTarget.resource(UUID.randomUUID(), InstitutionWorkGroup.LLM, UUID.randomUUID())).allowed()).isFalse();
    }

    @Test
    void currentRecipientRequiresTheActualDecisionKindAndTheSameLiveWorkspaceScope() {
        UUID selectedWorkspace = workspace();
        UUID otherWorkspace = workspace();
        User requester = user(UserRole.USER);
        change(subject, listed(0, InstitutionWork.VM_REQUEST_DECIDE, List.of(selectedWorkspace)));
        operations.saveMail(principal(sys), org.getPublicId(),
                new SaveOrgRequestMailSettingsRequest(orgRevision(), RequestMailMode.DESIGNATED,
                        Set.of(subject.getPublicId()), null), "127.0.0.1");
        assertThat(currentRecipients(requester, ResourceType.VM, selectedWorkspace)).containsExactly(subject.getPublicId());
        assertThat(currentRecipients(requester, ResourceType.VM, otherWorkspace)).isEmpty();
        assertThat(currentRecipients(requester, ResourceType.LLM_API_KEY, selectedWorkspace)).isEmpty();
        jdbc.update("update workspaces set deleted_at=now() where public_id=?", selectedWorkspace);
        expectStatus(HttpStatus.NOT_FOUND, () -> currentRecipients(requester, ResourceType.VM, selectedWorkspace));
    }

    @Test
    void losingOneDecisionKindWarnsWithoutIntroducingANewMinimumStaffGate() {
        change(subject, selected(0, List.of(InstitutionWork.VM_REQUEST_DECIDE)));
        User llmDecider = user(UserRole.USER);
        change(llmDecider, selected(0, List.of(InstitutionWork.LLM_REQUEST_DECIDE)));
        jdbc.update("update users set status='DISABLED' where id=?", admin.getId());
        var body = new SaveOrgOperationsRequest(orgRevision(), Map.of(admin.getPublicId(), sourceRevision(admin),
                        subject.getPublicId(), sourceRevision(subject), llmDecider.getPublicId(), sourceRevision(llmDecider)),
                List.of(new SaveOrgOperationsRequest.Assignment(admin.getPublicId(), full(sourceRevision(admin)), false),
                        new SaveOrgOperationsRequest.Assignment(subject.getPublicId(),
                                selected(sourceRevision(subject), List.of(InstitutionWork.VM_METADATA_READ)), false),
                        new SaveOrgOperationsRequest.Assignment(llmDecider.getPublicId(),
                                selected(sourceRevision(llmDecider), List.of(InstitutionWork.LLM_REQUEST_DECIDE)), false)),
                RequestMailMode.DESIGNATED, null, false, null);
        var preview = operations.preview(principal(sys), org.getPublicId(), body);
        assertThat(preview.createsStaffVacancy()).isFalse();
        assertThat(preview.warnings()).anySatisfy(warning -> assertThat(warning).startsWith("VM:"));
        var saved = operations.save(principal(sys), org.getPublicId(), body, "127.0.0.1");
        assertThat(saved.activeAdminCount()).isZero();
        assertThat(saved.activeApproverCount()).isEqualTo(1);
    }

    @Test
    void losingTheLastActualDeciderRequiresTheExistingSystemVacancyOverride() {
        change(subject, selected(0, List.of(InstitutionWork.VM_REQUEST_DECIDE)));
        jdbc.update("update users set status='DISABLED' where id=?", admin.getId());
        var body = rosterWithSubject(selected(sourceRevision(subject), List.of(InstitutionWork.VM_METADATA_READ)));
        var preview = operations.preview(principal(sys), org.getPublicId(), body);
        assertThat(preview.before().activeAdminCount()).isZero();
        assertThat(preview.before().activeApproverCount()).isEqualTo(1);
        assertThat(preview.after().activeApproverCount()).isZero();
        assertThat(preview.createsStaffVacancy()).isTrue();
        expectStatus(HttpStatus.CONFLICT, () -> operations.save(principal(sys), org.getPublicId(), body, "127.0.0.1"));
        var confirmed = new SaveOrgOperationsRequest(body.expectedRevision(), body.expectedPermissionRevisions(), body.members(),
                body.mailMode(), "결정 담당 공백 확인", true, org.getPublicId());
        assertThat(operations.save(principal(sys), org.getPublicId(), confirmed, "127.0.0.1").activeApproverCount()).isZero();
    }

    @Test
    void suspensionAndReactivationRecordActualDecisionKindsWithoutChangingThePermissionSource() {
        change(subject, selected(0, List.of(InstitutionWork.VM_REQUEST_DECIDE)));
        User llmDecider = user(UserRole.USER);
        change(llmDecider, selected(0, List.of(InstitutionWork.LLM_REQUEST_DECIDE)));
        jdbc.update("update users set status='DISABLED' where id=?", admin.getId());
        var permissions = storedPermissions();
        long epoch = orgRevision();
        adminUsers.disable(principal(sys), subject.getPublicId(), "격리 검증", "127.0.0.1");
        assertThat(jdbc.queryForObject("select detail->'uncoveredKinds' from audit_logs"
                        + " where target_org_id=? and action='org.staff_status_update' order by id desc limit 1",
                String.class, org.getId())).isEqualTo("[\"VM\"]");
        assertThat(query.get(principal(sys), org.getPublicId(), null).activeApproverCount()).isEqualTo(1);
        assertThat(actors.authenticate(subject.getPublicId(), 0)).isEmpty();
        adminUsers.enable(principal(sys), subject.getPublicId(), "127.0.0.1");
        assertThat(query.get(principal(sys), org.getPublicId(), null).activeApproverCount()).isEqualTo(2);
        assertThat(orgRevision()).isEqualTo(epoch + 2);
        assertThat(storedPermissions()).isEqualTo(permissions);
        assertThat(actors.authenticate(subject.getPublicId(), 0)).isEmpty();
        assertThat(actors.authenticate(subject.getPublicId(), 1)).isPresent();
    }

    @Test
    void displayRoleFromAnotherInstitutionNeverCountsAsAnActualFullSourceHere() {
        change(subject, selected(0, List.of(InstitutionWork.VM_METADATA_READ)));
        Org other = orgs.saveAndFlush(new Org("표시 역할 출처 " + UUID.randomUUID(), null));
        SeedFixtures.grantOrgRole(jdbc, subject.getId(), other.getId(), UserRole.ORG_ADMIN);
        jdbc.update("update users set status='DISABLED' where id=?", admin.getId());
        var snapshot = query.get(principal(sys), org.getPublicId(), null);
        assertThat(users.findById(subject.getId()).orElseThrow().getRole()).isEqualTo(UserRole.ORG_ADMIN);
        assertThat(snapshot.activeAdminCount()).isZero();
        assertThat(snapshot.activeApproverCount()).isZero();
        assertThat(snapshot.members()).filteredOn(member -> member.userId().equals(subject.getPublicId()))
                .singleElement().satisfies(member -> assertThat(member.permission().mode()).isEqualTo(InstitutionPermissionMode.SELECTED));
    }

    @Test
    void urgentSystemSuspensionSurvivesUnknownInstitutionStaffingWithoutFabricatingCounts() throws Exception {
        change(subject, selected(0, List.of(InstitutionWork.VM_METADATA_READ)));
        jdbc.update("update users set role='USER' where id=?", subject.getId());
        var permissions = storedPermissions();
        long epoch = orgRevision();

        mvc.perform(post("/api/v1/admin/users/" + subject.getPublicId() + "/disable")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(sys))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"격리 긴급 정지 검증\"}"))
                .andExpect(status().isOk());

        assertThat(users.findById(subject.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.DISABLED);
        assertThat(actors.authenticate(subject.getPublicId(), 0)).isEmpty();
        assertThat(jdbc.queryForObject("select token_version from users where id=?", Integer.class, subject.getId())).isEqualTo(1);
        assertThat(orgRevision()).isEqualTo(epoch + 1);
        assertThat(storedPermissions()).isEqualTo(permissions);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where target_org_id=?"
                        + " and detail->>'staffingUnavailable'='true' and not jsonb_exists(detail,'activeAdminCount')"
                        + " and not jsonb_exists(detail,'activeApproverCount') and not jsonb_exists(detail,'decidersByKind')",
                Integer.class, org.getId())).isEqualTo(1);
        expectStatus(HttpStatus.CONFLICT, () -> query.get(principal(sys), org.getPublicId(), null));
        expectStatus(HttpStatus.CONFLICT, () -> accountStatusChanges.requireMayWithdraw(admin.getId()));
    }

    @Test
    void failedStaffingUnavailableAuditStillRollsBackUrgentSuspension() throws Exception {
        change(subject, selected(0, List.of(InstitutionWork.VM_METADATA_READ)));
        jdbc.update("update users set role='USER' where id=?", subject.getId());
        var permissions = storedPermissions();
        long epoch = orgRevision();
        AuditService auditTarget = AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new IllegalStateException("synthetic staffing audit failure")).when(auditTarget)
                .recordOrgChange(any(), anyString(), anyString(), any(), anyString(), any(), any());

        mvc.perform(post("/api/v1/admin/users/" + subject.getPublicId() + "/disable")
                        .header("Authorization", "Bearer " + jwt.createAccessToken(sys))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"격리 감사 실패 검증\"}"))
                .andExpect(status().isInternalServerError());

        assertThat(users.findById(subject.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(jdbc.queryForObject("select token_version from users where id=?", Integer.class, subject.getId())).isZero();
        assertThat(orgRevision()).isEqualTo(epoch);
        assertThat(storedPermissions()).isEqualTo(permissions);
    }

    private List<UUID> currentRecipients(User requester, ResourceType type, UUID workspace) {
        long internalWorkspace = jdbc.queryForObject("select id from workspaces where public_id=?", Long.class, workspace);
        return new TransactionTemplate(transactions).execute(tx -> {
            administrationLock.acquire();
            return query.forSelection(org.getPublicId(), requester.getPublicId(), type, internalWorkspace).members().stream()
                    .filter(member -> Boolean.TRUE.equals(member.currentMailRecipient())).map(member -> member.userId()).toList();
        });
    }

    private SaveOrgOperationsRequest rosterWithSubject(InstitutionPermissionInput permission) {
        return new SaveOrgOperationsRequest(orgRevision(), Map.of(admin.getPublicId(), sourceRevision(admin),
                        subject.getPublicId(), sourceRevision(subject)),
                List.of(new SaveOrgOperationsRequest.Assignment(admin.getPublicId(), full(sourceRevision(admin)), false),
                        new SaveOrgOperationsRequest.Assignment(subject.getPublicId(), permission, false)),
                RequestMailMode.DESIGNATED, null, false, null);
    }

    private void change(User target, InstitutionPermissionInput permission) {
        operations.saveSingle(principal(sys), org.getPublicId(), target.getPublicId(), permission, false,
                orgRevision(), permission.expectedRevision(), "127.0.0.1");
    }

    private InstitutionPermissionInput selected(long revision, List<InstitutionWork> work) {
        Map<InstitutionWorkGroup, InstitutionWorkspaceScopeInput> scopes = new LinkedHashMap<>();
        work.stream().map(InstitutionWork::group).filter(java.util.Objects::nonNull).distinct()
                .forEach(group -> scopes.put(group,
                        new InstitutionWorkspaceScopeInput(InstitutionWorkspaceScopeMode.ALL, List.of())));
        return new InstitutionPermissionInput(InstitutionPermissionMode.SELECTED,
                work.stream().map(InstitutionWork::id).toList(), scopes, revision);
    }

    private InstitutionPermissionInput listed(long revision, InstitutionWork work, List<UUID> workspaces) {
        return new InstitutionPermissionInput(InstitutionPermissionMode.SELECTED, List.of(work.id()),
                Map.of(work.group(), new InstitutionWorkspaceScopeInput(InstitutionWorkspaceScopeMode.LISTED, workspaces)), revision);
    }

    private InstitutionAuthority authority(User user) {
        return actors.authenticate(user.getPublicId(), 0).orElseThrow().institutionContext().authority().orElseThrow();
    }

    private List<Map<String, Object>> storedPermissions() {
        return jdbc.queryForList("select user_id,role::text,permission_mode,permission_payload::text,permission_revision"
                + " from user_org_roles where org_id=? order by user_id", org.getId());
    }

    private long accountRevision(User user) {
        return jdbc.queryForObject("select row_revision from users where id=?", Long.class, user.getId());
    }

    private UUID workspace() {
        return jdbc.queryForObject("insert into workspaces(kind,name) values ('PROJECT',?) returning public_id",
                UUID.class, "명시 범위 " + UUID.randomUUID());
    }

    private void expectStatus(HttpStatus status, Runnable command) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(ApiException.class,
                exception -> assertThat(exception.getStatus()).isEqualTo(status));
    }

    private SaveOrgOperationsRequest unchangedRoster() {
        long revision = sourceRevision(admin);
        return new SaveOrgOperationsRequest(orgRevision(), Map.of(admin.getPublicId(), revision),
                List.of(new SaveOrgOperationsRequest.Assignment(admin.getPublicId(), full(revision), false)),
                RequestMailMode.DESIGNATED, null, false, null);
    }

    private InstitutionPermissionInput full(long revision) {
        return new InstitutionPermissionInput(InstitutionPermissionMode.FULL, List.of(), Map.of(), revision);
    }

    private AuthenticatedUser principal(User user) {
        return actors.authenticate(user.getPublicId(), user.getTokenVersion()).orElseThrow();
    }

    private long sourceRevision(User user) {
        return jdbc.queryForObject("select permission_revision from user_org_roles where user_id=? and org_id=?",
                Long.class, user.getId(), org.getId());
    }

    private long orgRevision() {
        return jdbc.queryForObject("select admin_revision from orgs where id=?", Long.class, org.getId());
    }

    private User user(UserRole role) {
        User user = new User("institution.atomic." + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", "원자 권한 검증");
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        return users.saveAndFlush(user);
    }
}
