package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.announcement.Announcement;
import kr.ac.pusan.pickle.announcement.AnnouncementRepository;
import kr.ac.pusan.pickle.announcement.AnnouncementScope;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.security.institution.InstitutionActorProvider;
import kr.ac.pusan.pickle.notice.NoticeService;
import kr.ac.pusan.pickle.notice.dto.NoticeCreateRequest;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentCodec;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkGroup;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.support.RequestFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserRole;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.workspace.Workspace;
import kr.ac.pusan.pickle.workspace.WorkspaceKind;
import kr.ac.pusan.pickle.workspace.WorkspaceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Selected governance work uses synthetic database sources independently of display roles. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InstitutionGovernanceTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository users;
    @Autowired private OrgRepository orgs;
    @Autowired private AnnouncementRepository announcements;
    @Autowired private JwtService jwt;
    @Autowired private AuditService audit;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private ObjectMapper json;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private InstitutionActorProvider actors;
    @Autowired private NoticeService noticeService;

    @Test
    void lookupExposesOnlyMinimalIdentityAndNeverBroadSupportOrSystemAccounts() throws Exception {
        User actor = user(UserRole.USER); User target = user(UserRole.USER); Org org = org();
        selected(actor, org, Set.of(InstitutionWork.STUDENT_LOOKUP), Map.of());
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId()).header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(target.getPublicId().toString()))
                .andExpect(jsonPath("$.studentNo").value(target.getStudentNo()))
                .andExpect(jsonPath("$.managedOrgs").value((Object) null))
                .andExpect(jsonPath("$.mfaEnabled").value((Object) null))
                .andExpect(jsonPath("$.memberships").value((Object) null))
                .andExpect(jsonPath("$.activeVmCount").value((Object) null))
                .andExpect(jsonPath("$.statusChanges").value((Object) null))
                .andExpect(jsonPath("$.position").value((Object) null));
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId() + "/support").header("Authorization", bearer(actor)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users").param("orgId", org.getPublicId().toString())
                        .header("Authorization", bearer(actor))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/users/" + target.getPublicId() + "/profile")
                        .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + target.getStudentNo() + "\"}"))
                .andExpect(status().isForbidden());
        User system = user(UserRole.SYS_ADMIN);
        mvc.perform(get("/api/v1/admin/users/" + system.getPublicId()).header("Authorization", bearer(actor)))
                .andExpect(status().isNotFound());
    }

    @Test
    void correctionAloneProvidesProfileAndPreviewWithoutBroadRelationships() throws Exception {
        User actor = user(UserRole.USER); User target = user(UserRole.USER);
        selected(actor, org(), Set.of(InstitutionWork.STUDENT_PROFILE_CORRECT), Map.of());
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId()).header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.position").value("STUDENT_UNDERGRAD"))
                .andExpect(jsonPath("$.supportCapabilities.canCorrectProfile").value(true))
                .andExpect(jsonPath("$.memberships").value((Object) null));
        mvc.perform(post("/api/v1/admin/users/" + target.getPublicId() + "/profile-impact")
                        .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PROFILE\",\"profile\":{\"studentNo\":\"" + target.getStudentNo() + "\"}}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId() + "/support").header("Authorization", bearer(actor)))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/users/" + target.getPublicId() + "/profile")
                        .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + target.getStudentNo() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.position").value("STUDENT_UNDERGRAD"))
                .andExpect(jsonPath("$.memberships").value((Object) null));
    }

    @Test
    void unrelatedWriteDisplayRoleDoesNotAcquireProfileCorrection() throws Exception {
        User actor = user(UserRole.USER); User target = user(UserRole.USER);
        selected(actor, org(), Set.of(InstitutionWork.NOTICE_MANAGE), Map.of());
        mvc.perform(post("/api/v1/admin/users/" + target.getPublicId() + "/profile-impact")
                        .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"PROFILE\",\"profile\":{\"studentNo\":\"" + target.getStudentNo() + "\"}}"))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/admin/users/" + target.getPublicId() + "/profile")
                        .header("Authorization", bearer(actor)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"" + target.getStudentNo() + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void supportReadKeepsStudentFieldsWithoutImplicitRelationshipWork() throws Exception {
        User actor = user(UserRole.USER); User target = user(UserRole.USER);
        selected(actor, org(), Set.of(InstitutionWork.STUDENT_SUPPORT_READ), Map.of());
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId()).header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberships").value((Object) null))
                .andExpect(jsonPath("$.activeVmCount").value((Object) null))
                .andExpect(jsonPath("$.mfaEnabled").value(false))
                .andExpect(jsonPath("$.supportCapabilities.canCorrectProfile").value(false));
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId() + "/support").header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.relationshipsLimited").value(true));
    }

    @Test
    void noticeReadIsSeparateFromAllFiveGlobalMutations() throws Exception {
        User actor = user(UserRole.USER); selected(actor, org(), Set.of(InstitutionWork.NOTICE_READ), Map.of());
        UUID id = UUID.randomUUID(); String token = bearer(actor);
        mvc.perform(get("/api/v1/admin/notices").header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/notices").header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Synthetic\",\"body\":\"Synthetic\"}")).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/admin/notices/" + id).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Synthetic\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/notices/" + id).header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/admin/notices/" + id + "/images")
                .file(new MockMultipartFile("file", "synthetic.png", "image/png", new byte[] {1}))
                .header("Authorization", token)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/notices/" + id + "/images/" + UUID.randomUUID())
                .header("Authorization", token)).andExpect(status().isForbidden());
    }

    @Test
    void noticeManagementAloneCanPublishAndReadItsResultWithoutStudentWork() throws Exception {
        User actor = user(UserRole.USER); selected(actor, org(), Set.of(InstitutionWork.NOTICE_MANAGE), Map.of());
        String body = mvc.perform(post("/api/v1/admin/notices").header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Synthetic\",\"body\":\"Synthetic\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(body).get("id").asString();
        mvc.perform(get("/api/v1/admin/notices/" + id).header("Authorization", bearer(actor))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/admin/notices/" + id).header("Authorization", bearer(actor))).andExpect(status().isNoContent());
    }

    @Test
    void announcementHistoryUsesCurrentAuthorRowsAndAllRatherThanSavedAudienceOrFull() throws Exception {
        Org first = org(); Org other = org(); User author = user(UserRole.USER); User reader = user(UserRole.USER);
        selected(author, first, Set.of(InstitutionWork.STUDENT_LOOKUP), Map.of());
        selected(reader, first, Set.of(InstitutionWork.INSTITUTION_ANNOUNCEMENT_READ), Map.of());
        Announcement historical = announcements.saveAndFlush(new Announcement(author.getId(), AnnouncementScope.ORG,
                other.getId(), null, "Synthetic history", "Immutable body"));
        historical.setRecipientCount(7); announcements.saveAndFlush(historical);
        mvc.perform(get("/api/v1/admin/announcements/" + historical.getPublicId()).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recipientCount").value(7))
                .andExpect(jsonPath("$.orgId").value(other.getPublicId().toString()));
        User system = user(UserRole.SYS_ADMIN);
        Announcement broadcast = announcements.saveAndFlush(new Announcement(system.getId(), AnnouncementScope.ALL,
                null, null, "Synthetic broadcast", "Immutable broadcast"));
        jdbc.update("delete from user_org_roles where user_id=? and org_id=?", author.getId(), first.getId());
        mvc.perform(get("/api/v1/admin/announcements/" + historical.getPublicId()).header("Authorization", bearer(reader)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/announcements/" + broadcast.getPublicId()).header("Authorization", bearer(reader)))
                .andExpect(status().isOk());
        assertThat(announcements.findById(historical.getId()).orElseThrow().getBody()).isEqualTo("Immutable body");
    }

    @Test
    void workspaceSendCannotJoinOneSourcesQualificationWithAnotherSourcesScope() throws Exception {
        Org first = org(); Org second = org(); User actor = user(UserRole.USER);
        Workspace allowedElsewhere = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic other", null));
        Workspace target = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic target", null));
        RequestFixtures.insertVmRequest(jdbc, target.getId(), first.getId(), actor.getId(), "Synthetic relation", null);
        Set<InstitutionWork> work = Set.of(InstitutionWork.WORKSPACE_ANNOUNCEMENT_SEND);
        selected(actor, first, work, Map.of(InstitutionWorkGroup.WORKSPACE, InstitutionWorkspaceScope.listed(Set.of(allowedElsewhere.getPublicId()))));
        selected(actor, second, work, Map.of(InstitutionWorkGroup.WORKSPACE, InstitutionWorkspaceScope.listed(Set.of(target.getPublicId()))));
        String form = "{\"scope\":\"WORKSPACE\",\"workspaceId\":\"" + target.getPublicId()
                + "\",\"title\":\"Synthetic\",\"body\":\"Synthetic\"}";
        mvc.perform(post("/api/v1/admin/announcements/preview").header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON).content(form)).andExpect(status().isNotFound());
        selected(actor, first, work, Map.of(InstitutionWorkGroup.WORKSPACE, InstitutionWorkspaceScope.listed(Set.of(target.getPublicId()))));
        mvc.perform(post("/api/v1/admin/announcements/preview").header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON).content(form)).andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(0));
        mvc.perform(post("/api/v1/admin/announcements/preview").header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"scope\":\"ALL\",\"title\":\"Synthetic\",\"body\":\"Synthetic\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void noticeImagesOutsideTheWindowRequireNoticeWorkRatherThanAnyInstitutionRole() throws Exception {
        User system = user(UserRole.SYS_ADMIN);
        String form = "{\"title\":\"Synthetic scheduled\",\"body\":\"Synthetic\",\"startsAt\":\"2099-01-01T00:00:00Z\"}";
        String notice = json.readTree(mvc.perform(post("/api/v1/admin/notices").header("Authorization", bearer(system))
                        .contentType(MediaType.APPLICATION_JSON).content(form)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        byte[] png = new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10, 0};
        String image = json.readTree(mvc.perform(multipart("/api/v1/admin/notices/" + notice + "/images")
                        .file(new MockMultipartFile("file", "synthetic.png", "image/png", png))
                        .header("Authorization", bearer(system))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).get("id").asString();
        User lookup = user(UserRole.USER); selected(lookup, org(), Set.of(InstitutionWork.STUDENT_LOOKUP), Map.of());
        String path = "/api/v1/notices/" + notice + "/images/" + image;
        mvc.perform(get(path).header("Authorization", bearer(lookup))).andExpect(status().isNotFound());
        User reader = user(UserRole.USER); selected(reader, org(), Set.of(InstitutionWork.NOTICE_READ), Map.of());
        mvc.perform(get(path).header("Authorization", bearer(reader))).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test
    void auditUsesVerifiedTargetsBeforePagingAndLeavesUnknownActorHistoryForSys() throws Exception {
        Org own = org(); Org foreign = org(); User reader = user(UserRole.USER);
        selected(reader, own, Set.of(InstitutionWork.INSTITUTION_AUDIT_READ), Map.of());
        String action = "synthetic.audit." + UUID.randomUUID();
        insertAudit(action, reader.getId(), "user", reader.getPublicId(), null);
        insertAudit(action, reader.getId(), "org", foreign.getPublicId(), null);
        insertAudit(action, null, "org", own.getPublicId(), null);
        for (String unverifiedId : java.util.List.of("not-a-uuid", "9223372036854775808")) {
            jdbc.update("insert into audit_logs(actor_id,actor_role,action,target_type,target_id) values(?,'USER',?,'org',?)",
                    reader.getId(), action, unverifiedId);
        }
        mvc.perform(get("/api/v1/admin/audit").param("action", action).param("size", "1").header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].targetOrgId").value(own.getPublicId().toString()))
                .andExpect(jsonPath("$.content[0].targetOrgName").value((Object) null));
        User sys = user(UserRole.SYS_VIEWER);
        mvc.perform(get("/api/v1/admin/audit").param("action", action).header("Authorization", bearer(sys)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5));
    }

    @Test
    void afterCommitAuditKeepsTargetFactsCapturedOnTheBusinessConnection() {
        Org target = org(); String before = target.getName(); String action = "synthetic.capture." + UUID.randomUUID();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            audit.recordAfterCommit(null, "SYSTEM", action, "org", target.getPublicId(), Map.of(), null);
            jdbc.update("update orgs set name=? where id=?", "Renamed after capture", target.getId());
        });
        assertThat(jdbc.queryForObject("select target_org_id from audit_logs where action=?", Long.class, action)).isEqualTo(target.getId());
        assertThat(jdbc.queryForObject("select target_org_name from audit_logs where action=?", String.class, action)).isEqualTo(before);
    }

    @Test
    void rootPolicyHistoryUsesItsSavedImmutableNameWithoutInventingAPublicUuid() throws Exception {
        Org own = org(); User reader = user(UserRole.USER);
        selected(reader, own, Set.of(InstitutionWork.INSTITUTION_AUDIT_READ), Map.of());
        String root = "synthetic-" + UUID.randomUUID() + ".invalid";
        jdbc.update("insert into domain_roots(root_domain,org_id) values(?,?)", root, own.getId());
        jdbc.update("insert into audit_logs(actor_role,action,target_type,target_id,detail) values('SYSTEM',?,'domain_root',null,?::jsonb)",
                AuditService.DOMAIN_ROOT_POLICY, "{\"rootDomain\":\"" + root + "\"}");
        mvc.perform(get("/api/v1/admin/audit").param("action", AuditService.DOMAIN_ROOT_POLICY)
                        .param("targetOrgId", own.getPublicId().toString()).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> audit.recordAfterCommit(null,
                "SYSTEM", AuditService.DOMAIN_ROOT_POLICY, "domain_root", null, Map.of("rootDomain", root), null));
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where action=? and target_org_id=?",
                Long.class, AuditService.DOMAIN_ROOT_POLICY, own.getId())).isEqualTo(1L);
    }

    @Test
    void announcementAuditUsesItsImmutableInstitutionTargetWithoutChangingHistoryVisibility() throws Exception {
        Org own = org(); Org authorOrg = org(); User author = user(UserRole.USER); User reader = user(UserRole.USER);
        selected(author, authorOrg, Set.of(InstitutionWork.STUDENT_LOOKUP), Map.of());
        selected(reader, own, Set.of(InstitutionWork.INSTITUTION_AUDIT_READ), Map.of());
        Announcement send = announcements.saveAndFlush(new Announcement(author.getId(), AnnouncementScope.ORG,
                own.getId(), null, "Synthetic target", "Immutable audience"));
        String action = "synthetic.announcement." + UUID.randomUUID();
        insertAudit(action, author.getId(), "announcement", send.getPublicId(), null);
        mvc.perform(get("/api/v1/admin/audit").param("action", action).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].targetOrgId").value(own.getPublicId().toString()));
        mvc.perform(get("/api/v1/admin/announcements/" + send.getPublicId()).header("Authorization", bearer(reader)))
                .andExpect(status().isForbidden());
    }

    @Test
    void inconsistentDisplayRoleNeverSuppliesWorkWhileAuthenticatedIdentitySurvives() throws Exception {
        User actor = user(UserRole.ORG_ADMIN); Org own = org();
        selected(actor, own, Set.of(InstitutionWork.STUDENT_LOOKUP), Map.of());
        jdbc.update("update users set role='ORG_ADMIN' where id=?", actor.getId());
        mvc.perform(get("/api/v1/me").header("Authorization", bearer(actor)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(actor.getPublicId().toString()));
        mvc.perform(get("/api/v1/admin/users").header("Authorization", bearer(actor))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit").header("Authorization", bearer(actor))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/notices").header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Synthetic\",\"body\":\"Synthetic\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void noticeMutationReloadsSourcesAfterTheAdministrationBarrier() {
        User actor = user(UserRole.USER); Org own = org();
        selected(actor, own, Set.of(InstitutionWork.NOTICE_MANAGE), Map.of());
        var original = actors.authenticate(actor.getPublicId(), actor.getTokenVersion()).orElseThrow();
        selected(actor, own, Set.of(InstitutionWork.NOTICE_READ), Map.of());
        Long before = jdbc.queryForObject("select count(*) from notices", Long.class);
        assertThatThrownBy(() -> noticeService.create(original,
                new NoticeCreateRequest("Synthetic", "Synthetic", false, false, null, null), null))
                .isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("select count(*) from notices", Long.class)).isEqualTo(before);
    }

    @Test
    void knownPreUuidTargetsRetainTheirInternalIdentityWhileObsoleteTypesRemainUnclassified() throws Exception {
        Org own = org(); User reader = user(UserRole.USER);
        selected(reader, own, Set.of(InstitutionWork.INSTITUTION_AUDIT_READ), Map.of());
        Workspace workspace = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic legacy target", null));
        long request = RequestFixtures.insertVmRequest(jdbc, workspace.getId(), own.getId(), reader.getId(), "Synthetic legacy relation", null);
        String action = "synthetic.numeric." + UUID.randomUUID();
        for (var target : Map.of("org", own.getId(), "request", request, "vm_request", request).entrySet()) {
            jdbc.update("insert into audit_logs(actor_role,action,target_type,target_id) values('SYSTEM',?,?,?)",
                    action, target.getKey(), target.getValue().toString());
        }
        mvc.perform(get("/api/v1/admin/audit").param("action", action).param("size", "1")
                        .header("Authorization", bearer(reader))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content.length()").value(1));
        User sys = user(UserRole.SYS_VIEWER);
        mvc.perform(get("/api/v1/admin/audit").param("action", action).header("Authorization", bearer(sys)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void profilePreviewBindsTheCurrentAuthorityRevisionWithoutChangingTheCandidate() throws Exception {
        User actor = user(UserRole.USER); User target = user(UserRole.USER); Org own = org();
        selected(actor, own, Set.of(InstitutionWork.STUDENT_PROFILE_CORRECT), Map.of());
        String path = "/api/v1/admin/users/" + target.getPublicId() + "/profile-impact";
        String form = "{\"action\":\"PROFILE\",\"profile\":{\"studentNo\":\"" + target.getStudentNo() + "\"}}";
        String first = json.readTree(mvc.perform(post(path).header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON).content(form)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("actorAccessImpact").get("fingerprint").asString();
        jdbc.update("update user_org_roles set permission_revision=nextval('institution_permission_revision_seq') where user_id=? and org_id=?",
                actor.getId(), own.getId());
        String second = json.readTree(mvc.perform(post(path).header("Authorization", bearer(actor))
                        .contentType(MediaType.APPLICATION_JSON).content(form)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("actorAccessImpact").get("fingerprint").asString();
        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void retiredPortMappingHistoryUsesOnlyItsActionsSavedServerVerifiedVmReference() throws Exception {
        Org own = org(); User reader = user(UserRole.USER);
        selected(reader, own, Set.of(InstitutionWork.INSTITUTION_AUDIT_READ), Map.of());
        Workspace workspace = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic retired mapping", null));
        Long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        Long node = jdbc.queryForObject("select min(id) from nodes", Long.class);
        long request = RequestFixtures.insertVmRequest(jdbc, workspace.getId(), own.getId(), reader.getId(), "Synthetic saved VM", image);
        String name = "synthetic-" + UUID.randomUUID();
        UUID vm = jdbc.queryForObject("insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)"
                        + " values(?,?,?,?,?,?,?,2,2048,10,'RUNNING') returning public_id", UUID.class,
                node, workspace.getId(), own.getId(), request, name, name, image);
        UUID mapping = UUID.randomUUID(); String detail = "{\"vmId\":\"" + vm + "\"}";
        jdbc.update("insert into audit_logs(actor_role,action,target_type,target_id,detail) values('SYSTEM',?,'port_mapping',?,?::jsonb)",
                AuditService.PORT_MAPPING_DELETE, mapping.toString(), detail);
        mvc.perform(get("/api/v1/admin/audit").param("targetId", mapping.toString()).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        new TransactionTemplate(transactions).executeWithoutResult(tx -> audit.recordAfterCommit(null, "SYSTEM",
                AuditService.PORT_MAPPING_DELETE, "port_mapping", mapping, Map.of("vmId", vm), null));
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where target_id=? and target_org_id=?",
                Long.class, mapping.toString(), own.getId())).isEqualTo(1L);
        String unverifiedAction = "synthetic.unverified.reference." + UUID.randomUUID();
        jdbc.update("insert into audit_logs(actor_role,action,target_type,target_id,detail) values('SYSTEM',?,'port_mapping',?,?::jsonb)",
                unverifiedAction, mapping.toString(), detail);
        mvc.perform(get("/api/v1/admin/audit").param("action", unverifiedAction).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void studentSupportAloneDoesNotExposeWorkspaceOrResourceRelationships() throws Exception {
        Org own = org(); Org foreign = org(); User reader = user(UserRole.USER); User target = user(UserRole.USER);
        selected(reader, own, Set.of(InstitutionWork.STUDENT_SUPPORT_READ), Map.of());
        Workspace workspace = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic withheld relation", null));
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'MEMBER')", workspace.getId(), target.getId());
        Long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        Long node = jdbc.queryForObject("select min(id) from nodes", Long.class);
        long request = RequestFixtures.insertVmRequest(jdbc, workspace.getId(), foreign.getId(), target.getId(), "Synthetic foreign relation", image);
        String name = "synthetic-withheld-" + UUID.randomUUID();
        jdbc.update("insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)"
                        + " values(?,?,?,?,?,?,?,2,2048,10,'RUNNING')", node, workspace.getId(), foreign.getId(), request, name, name, image);
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId() + "/support").header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberships").value((Object) null))
                .andExpect(jsonPath("$.resources").value((Object) null)).andExpect(jsonPath("$.requests").value((Object) null))
                .andExpect(jsonPath("$.invitations").value((Object) null)).andExpect(jsonPath("$.relationshipsLimited").value(true));
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId()).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberships").value((Object) null))
                .andExpect(jsonPath("$.activeVmCount").value((Object) null));
        mvc.perform(get("/api/v1/admin/users").param("orgId", foreign.getPublicId().toString()).param("q", target.getEmail())
                        .header("Authorization", bearer(reader))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void relationshipRowsKeepTheirOwnKindInstitutionAndWorkspaceScopeBeforeProjection() throws Exception {
        Org own = org(); Org foreign = org(); User reader = user(UserRole.USER); User target = user(UserRole.USER);
        Workspace first = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic visible", null));
        Workspace second = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic hidden", null));
        membership(first, target); membership(second, target);
        virtualMachine(first, own, target); virtualMachine(first, foreign, target); virtualMachine(second, own, target);
        jdbc.update("insert into domains(workspace_id,org_id,kind,fqdn,root_domain,status,renew_due_at)"
                        + " values(?,?,'EXTERNAL',?,'pusan.dev','ACTIVE',now()+interval '30 days')",
                first.getId(), own.getId(), "synthetic-" + UUID.randomUUID() + ".pusan.dev");
        selected(reader, own, Set.of(InstitutionWork.STUDENT_SUPPORT_READ, InstitutionWork.WORKSPACE_SUPPORT_READ, InstitutionWork.VM_METADATA_READ),
                Map.of(InstitutionWorkGroup.WORKSPACE, InstitutionWorkspaceScope.listed(Set.of(first.getPublicId())),
                        InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(Set.of(first.getPublicId()))));
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId() + "/support").header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberships.length()").value(1))
                .andExpect(jsonPath("$.memberships[0].workspaceId").value(first.getPublicId().toString()))
                .andExpect(jsonPath("$.memberships[0].resourceCounts[0].count").value((Object) null))
                .andExpect(jsonPath("$.resources.length()").value(1)).andExpect(jsonPath("$.resources[0].type").value("VM"))
                .andExpect(jsonPath("$.resources[0].standingRights").value((Object) null))
                .andExpect(jsonPath("$.resources[0].canRevoke").value((Object) null))
                .andExpect(jsonPath("$.resources[0].requestId").value((Object) null))
                .andExpect(jsonPath("$.requests").value((Object) null)).andExpect(jsonPath("$.relationshipsLimited").value(true));
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId()).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberships.length()").value(1))
                .andExpect(jsonPath("$.memberships[0].vmOrgIds.length()").value(1))
                .andExpect(jsonPath("$.memberships[0].vmOrgIds[0]").value(own.getPublicId().toString()))
                .andExpect(jsonPath("$.activeVmCount").value((Object) null));
        mvc.perform(get("/api/v1/admin/users").param("orgId", own.getPublicId().toString()).param("q", target.getEmail())
                        .header("Authorization", bearer(reader))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].relationshipsLimited").value(true));
        mvc.perform(get("/api/v1/admin/users").param("orgId", foreign.getPublicId().toString()).param("q", target.getEmail())
                        .header("Authorization", bearer(reader))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void scopedGlobalMetadataCanCountItsWorkspaceButCannotPretendToKnowTheUsersTotal() throws Exception {
        Org own = org(); User reader = user(UserRole.USER); User target = user(UserRole.USER);
        Workspace first = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic count visible", null));
        Workspace second = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic count hidden", null));
        membership(first, target); membership(second, target); virtualMachine(first, own, target); virtualMachine(second, own, target);
        Set<InstitutionWork> work = Set.of(InstitutionWork.STUDENT_SUPPORT_READ, InstitutionWork.WORKSPACE_SUPPORT_READ,
                InstitutionWork.VM_METADATA_SUPPORT_READ);
        selected(reader, own, work, Map.of(InstitutionWorkGroup.WORKSPACE, InstitutionWorkspaceScope.listed(Set.of(first.getPublicId())),
                InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(Set.of(first.getPublicId()))));
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId() + "/support").header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberships[0].resourceCounts[0].count").value(1));
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId()).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeVmCount").value((Object) null));
        selected(reader, own, work, Map.of(InstitutionWorkGroup.WORKSPACE, InstitutionWorkspaceScope.listed(Set.of(first.getPublicId())),
                InstitutionWorkGroup.VM, InstitutionWorkspaceScope.all()));
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId()).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeVmCount").value(2));
    }

    @Test
    void aclReadAloneDiagnosesOnlyTheBoundResourceAndItsRelatedAccounts() throws Exception {
        Org own = org(); User reader = user(UserRole.USER); User relatedSystem = user(UserRole.SYS_VIEWER);
        User unrelatedSystem = user(UserRole.SYS_VIEWER);
        Workspace workspace = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic ACL bound", null));
        membership(workspace, relatedSystem); UUID vm = virtualMachine(workspace, own, relatedSystem);
        selected(reader, own, Set.of(InstitutionWork.VM_ACL_SUPPORT_READ),
                Map.of(InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(Set.of(workspace.getPublicId()))));
        mvc.perform(get("/api/v1/admin/users/" + relatedSystem.getPublicId() + "/support/access")
                        .param("type", "VM").param("resourceId", vm.toString()).header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userStatus").value("ACTIVE"));
        mvc.perform(get("/api/v1/admin/users/" + unrelatedSystem.getPublicId() + "/support/access")
                        .param("type", "VM").param("resourceId", vm.toString()).header("Authorization", bearer(reader)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/users/" + relatedSystem.getPublicId()).header("Authorization", bearer(reader)))
                .andExpect(status().isNotFound());
    }

    @Test
    void explicitLegacyAllPresetsRetainTheCompleteRelationshipGraph() throws Exception {
        Org own = org(); Org foreign = org(); User target = user(UserRole.USER);
        Workspace first = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic legacy first", null));
        Workspace second = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic legacy second", null));
        membership(first, target); membership(second, target); virtualMachine(first, own, target); virtualMachine(second, foreign, target);
        for (UserRole role : java.util.List.of(UserRole.ORG_ADMIN, UserRole.ORG_MANAGER, UserRole.ORG_VIEWER)) {
            User reader = user(role); SeedFixtures.grantOrgRole(jdbc, reader.getId(), own.getId(), role);
            mvc.perform(get("/api/v1/admin/users/" + target.getPublicId() + "/support").header("Authorization", bearer(reader)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.memberships.length()").value(2))
                    .andExpect(jsonPath("$.resources.length()").value(2)).andExpect(jsonPath("$.resources[0].standingRights").isBoolean())
                    .andExpect(jsonPath("$.requests.length()").value(2)).andExpect(jsonPath("$.relationshipsLimited").value(false));
            mvc.perform(get("/api/v1/admin/users/" + target.getPublicId()).header("Authorization", bearer(reader)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.memberships.length()").value(2))
                    .andExpect(jsonPath("$.activeVmCount").value(2)).andExpect(jsonPath("$.relationshipsLimited").value(false));
        }
    }

    @Test
    void requestAndInvitationRelationshipsUseTheirOwnCompleteSourceBranches() throws Exception {
        Org own = org(); Org foreign = org(); User reader = user(UserRole.USER); User target = user(UserRole.USER);
        Workspace first = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic request first", null));
        Workspace second = workspaces.saveAndFlush(new Workspace(WorkspaceKind.COURSE, "Synthetic request second", null));
        membership(first, target); membership(second, target);
        UUID ownFirst = virtualMachine(first, own, target); virtualMachine(first, foreign, target); virtualMachine(second, own, target);
        UUID ownFirstRequest = jdbc.queryForObject("select r.public_id from requests r join vms v on v.request_id=r.id where v.public_id=?", UUID.class, ownFirst);
        User inviter = user(UserRole.SYS_ADMIN);
        for (Workspace workspace : java.util.List.of(first, second)) {
            jdbc.update("insert into workspace_invitations(workspace_id,invitee_email,role,invited_by) values(?,?,'MEMBER',?)",
                    workspace.getId(), target.getEmail(), inviter.getId());
        }
        Set<InstitutionWork> work = Set.of(InstitutionWork.STUDENT_SUPPORT_READ, InstitutionWork.WORKSPACE_SUPPORT_READ, InstitutionWork.VM_REQUEST_READ);
        selected(reader, own, work, Map.of(InstitutionWorkGroup.WORKSPACE, InstitutionWorkspaceScope.listed(Set.of(first.getPublicId())),
                InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(Set.of(first.getPublicId()))));
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId() + "/support").header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requests.length()").value(1))
                .andExpect(jsonPath("$.requests[0].id").value(ownFirstRequest.toString()))
                .andExpect(jsonPath("$.requests[0].resourceId").value((Object) null))
                .andExpect(jsonPath("$.invitations.length()").value(1))
                .andExpect(jsonPath("$.invitations[0].workspaceId").value(first.getPublicId().toString()))
                .andExpect(jsonPath("$.resources").value((Object) null));
        selected(reader, own, work, Map.of(InstitutionWorkGroup.WORKSPACE, InstitutionWorkspaceScope.listed(Set.of(first.getPublicId())),
                InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(Set.of(second.getPublicId()))));
        selected(reader, foreign, Set.of(InstitutionWork.VM_REQUEST_READ),
                Map.of(InstitutionWorkGroup.VM, InstitutionWorkspaceScope.listed(Set.of(first.getPublicId()))));
        mvc.perform(get("/api/v1/admin/users/" + target.getPublicId() + "/support").header("Authorization", bearer(reader)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requests.length()").value(2))
                .andExpect(jsonPath("$.requests[?(@.id == '" + ownFirstRequest + "')]").isEmpty());
    }

    private void membership(Workspace workspace, User target) {
        jdbc.update("insert into workspace_members(workspace_id,user_id,role) values(?,?,'MEMBER')", workspace.getId(), target.getId());
    }

    private UUID virtualMachine(Workspace workspace, Org org, User target) {
        Long image = jdbc.queryForObject("select min(id) from os_images", Long.class);
        Long node = jdbc.queryForObject("select min(id) from nodes", Long.class);
        long request = RequestFixtures.insertVmRequest(jdbc, workspace.getId(), org.getId(), target.getId(), "Synthetic relation", image);
        String name = "synthetic-" + UUID.randomUUID();
        return jdbc.queryForObject("insert into vms(node_id,workspace_id,org_id,request_id,name,hostname,image_id,vcpu,memory_mb,disk_gb,status)"
                        + " values(?,?,?,?,?,?,?,2,2048,10,'RUNNING') returning public_id", UUID.class,
                node, workspace.getId(), org.getId(), request, name, name, image);
    }

    private void insertAudit(String action, Long actorId, String type, UUID target, Long targetOrg) {
        jdbc.update("insert into audit_logs(actor_id,actor_role,action,target_type,target_id,target_org_id) values(?,?,?,?,?,?)",
                actorId, actorId == null ? "SYSTEM" : "USER", action, type, target.toString(), targetOrg);
    }

    private Org org() { return orgs.saveAndFlush(new Org("Synthetic " + UUID.randomUUID(), null)); }

    private User user(UserRole role) {
        User user = new User("governance-" + UUID.randomUUID() + "@example.edu", "{test-no-login}", "Synthetic account");
        user.setRole(role); user.setStatus(UserStatus.ACTIVE); user.setEmailVerifiedAt(Instant.now());
        user.setProfile(UserPosition.STUDENT_UNDERGRAD, "S" + UUID.randomUUID().toString().replace("-", "").substring(0, 9), null, "Synthetic department");
        return users.saveAndFlush(user);
    }

    private void selected(User actor, Org org, Set<InstitutionWork> work,
            Map<InstitutionWorkGroup, InstitutionWorkspaceScope> scopes) {
        InstitutionAssignment assignment = InstitutionAssignment.selected(actor.getPublicId(), org.getPublicId(), work, scopes, 0);
        var encoded = new InstitutionAssignmentCodec().encode(assignment);
        SeedFixtures.grantOrgRole(jdbc, actor.getId(), org.getId(), assignment.displayRole());
        jdbc.update("update user_org_roles set permission_mode=?,permission_payload=?::jsonb,permission_revision=nextval('institution_permission_revision_seq') where user_id=? and org_id=?",
                encoded.mode(), encoded.payload(), actor.getId(), org.getId());
        jdbc.update("update users set role=?::user_role where id=?", assignment.displayRole().name(), actor.getId());
    }

    private String bearer(User user) { return "Bearer " + jwt.createAccessToken(user); }
}
