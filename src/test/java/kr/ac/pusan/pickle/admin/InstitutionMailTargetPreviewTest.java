package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignment;
import kr.ac.pusan.pickle.security.institution.InstitutionAssignmentCodec;
import kr.ac.pusan.pickle.security.institution.InstitutionWork;
import kr.ac.pusan.pickle.security.institution.InstitutionWorkspaceScope;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.UserRepository;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class InstitutionMailTargetPreviewTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService tokens;
    @Autowired UserRepository users;
    @Autowired ObjectMapper json;
    private long org;
    private UUID orgId;
    private long firstWorkspace;
    private long secondWorkspace;
    private UUID firstId;
    private UUID secondId;
    private long configurer;
    private long vmFirst;
    private long llmFirst;
    private long vmSecond;

    @BeforeEach void setup() {
        org=jdbc.queryForObject("insert into orgs(name,request_mail_mode) values(?,'DESIGNATED') returning id",Long.class,"메일 대상 기관 "+UUID.randomUUID());
        orgId=pub("orgs",org);
        firstWorkspace=workspace("실제 신청 공간");secondWorkspace=workspace("앞으로 사용할 공간");
        firstId=pub("workspaces",firstWorkspace);secondId=pub("workspaces",secondWorkspace);
        configurer=actor("수신 설정",InstitutionWork.INSTITUTION_REQUEST_MAIL_CONFIGURE,null);
        vmFirst=actor("VM 첫 공간",InstitutionWork.VM_REQUEST_DECIDE,firstId);
        llmFirst=actor("LLM 첫 공간",InstitutionWork.LLM_REQUEST_DECIDE,firstId);
        vmSecond=actor("VM 둘째 공간",InstitutionWork.VM_REQUEST_DECIDE,secondId);
        jdbc.update("update user_org_roles set request_mail=true where org_id=? and user_id<>?",org,configurer);
        jdbc.update("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values('VM',?,?,?,'대상 참조','VM 신청')",firstWorkspace,org,configurer);
        jdbc.update("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values('LLM_API_KEY',?,?,?,'대상 참조','LLM 신청')",firstWorkspace,org,configurer);
        long foreignOrg=jdbc.queryForObject("insert into orgs(name) values(?) returning id",Long.class,"보이면 안 되는 기관 "+UUID.randomUUID());
        jdbc.update("insert into requests(resource_type,workspace_id,org_id,requester_id,purpose,display_name) values('DOMAIN',?,?,?,'다른 기관','다른 기관 신청')",secondWorkspace,foreignOrg,configurer);
    }

    @Test void exactKindAndWorkspaceProjectRecipientsWithoutExposingAssignmentsOrOtherInstitutionReferences() throws Exception {
        String permissions=payloads();long queue=queued();
        JsonNode result=read(configurer,"VM",firstId,null);
        assertThat(result.path("selectionContext").path("requestType").asString()).isEqualTo("VM");
        assertThat(result.path("selectionContext").path("workspaceId").asString()).isEqualTo(firstId.toString());
        assertThat(result.path("selectionTargets").size()).isEqualTo(2);
        assertThat(result.path("selectionTargets").toString()).doesNotContain(secondId.toString(),"DOMAIN");
        assertThat(member(result,vmFirst).path("currentMailRecipient").asBoolean()).isTrue();
        assertThat(member(result,llmFirst).path("exclusionReason").asString()).isEqualTo("DECISION_WORK_MISSING");
        assertThat(member(result,vmSecond).path("exclusionReason").asString()).isEqualTo("TARGET_SCOPE_EXCLUDED");
        assertThat(member(result,llmFirst).path("requestMail").asBoolean()).isTrue();
        assertThat(member(result,vmSecond).path("requestMail").asBoolean()).isTrue();
        for(JsonNode row:result.path("members")) { assertThat(row.path("permission").isNull()).isTrue(); }
        assertThat(payloads()).isEqualTo(permissions);assertThat(queued()).isEqualTo(queue);
    }

    @Test void receiptOnlyPreviewKeepsOtherKindsSelectedAndMakesNoQueueOrAuthorityWrite() throws Exception {
        String permissions=payloads();long queue=queued();
        String body=json.writeValueAsString(Map.of("expectedRevision",0,"mailMode","DESIGNATED","recipientIds",
                List.of(pub("users",vmFirst),pub("users",llmFirst),pub("users",vmSecond))));
        JsonNode result=json.readTree(mvc.perform(post(path()+"/request-mail/preview").param("requestType","LLM_API_KEY")
                .param("workspaceId",firstId.toString()).header("Authorization",bearer(configurer)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode after=result.path("after");
        assertThat(after.path("currentMailRecipientCount").asInt()).isEqualTo(1);
        assertThat(member(after,llmFirst).path("currentMailRecipient").asBoolean()).isTrue();
        assertThat(member(after,vmFirst).path("requestMail").asBoolean()).isTrue();
        assertThat(member(after,vmFirst).path("currentMailRecipient").asBoolean()).isFalse();
        assertThat(after.path("selectionContext").path("requestType").asString()).isEqualTo("LLM_API_KEY");
        assertThat(payloads()).isEqualTo(permissions);assertThat(queued()).isEqualTo(queue);
        assertThat(jdbc.queryForObject("select admin_revision from orgs where id=?",Long.class,org)).isZero();
    }

    @Test void knownFutureTargetNeedsNoExistingRequestAndRequesterExclusionDoesNotCreateFallback() throws Exception {
        JsonNode future=read(configurer,"VM",secondId,null);
        assertThat(future.path("currentMailRecipientCount").asInt()).isEqualTo(1);
        assertThat(member(future,vmSecond).path("currentMailRecipient").asBoolean()).isTrue();
        JsonNode excluded=read(configurer,"VM",firstId,pub("users",vmFirst));
        assertThat(excluded.path("currentMailRecipientCount").asInt()).isZero();
        assertThat(member(excluded,vmFirst).path("exclusionReason").asString()).isEqualTo("REQUESTER_EXCLUDED");
        assertThat(excluded.path("legacyFallback").asBoolean()).isFalse();
    }

    @Test void staffOnlyReadingDoesNotGainMailTargetDiscoveryOrRecipientProjection() throws Exception {
        long reader=actor("권한 읽기",InstitutionWork.INSTITUTION_STAFF_READ,null);
        JsonNode result=json.readTree(mvc.perform(get(path()).header("Authorization",bearer(reader))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(result.path("selectionTargets").isNull()).isTrue();
        assertThat(result.path("selectionContext").isNull()).isTrue();
        assertThat(result.path("currentMailRecipientCount").isNull()).isTrue();
        mvc.perform(get(path()).param("requestType","VM").param("workspaceId",firstId.toString())
                .header("Authorization",bearer(reader))).andExpect(status().isNotFound());
    }

    @Test void targetContextRequiresBothFieldsAndAnExistingLiveWorkspace() throws Exception {
        mvc.perform(get(path()).param("requestType","VM").header("Authorization",bearer(configurer))).andExpect(status().isUnprocessableContent());
        mvc.perform(get(path()).param("workspaceId",firstId.toString()).header("Authorization",bearer(configurer))).andExpect(status().isUnprocessableContent());
        mvc.perform(get(path()).param("requestType","VM").param("workspaceId",UUID.randomUUID().toString())
                .header("Authorization",bearer(configurer))).andExpect(status().isNotFound());
        jdbc.update("update workspaces set deleted_at=now(),deleted_by=? where id=?",SeedFixtures.sysadminId(jdbc),secondWorkspace);
        mvc.perform(get(path()).param("requestType","VM").param("workspaceId",secondId.toString())
                .header("Authorization",bearer(configurer))).andExpect(status().isNotFound());
    }

    private JsonNode read(long actor,String type,UUID workspace,UUID requester) throws Exception {
        var request=get(path()).param("requestType",type).param("workspaceId",workspace.toString()).header("Authorization",bearer(actor));
        if(requester!=null) { request.param("requesterId",requester.toString()); }
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode member(JsonNode result,long user) {
        String id=pub("users",user).toString();for(JsonNode row:result.path("members")) { if(id.equals(row.path("userId").asString())) { return row; } }
        throw new AssertionError("Missing staff reference "+id);
    }
    private long actor(String name,InstitutionWork work,UUID workspace) {
        long id=jdbc.queryForObject("insert into users(email,password_hash,name,role,status) values(?,?,?,'USER','ACTIVE') returning id",Long.class,
                "mail.preview."+UUID.randomUUID()+"@example.invalid","{test-no-login}",name);
        var assignment=InstitutionAssignment.selected(pub("users",id),orgId,Set.of(work),work.group()==null ? Map.of()
                : Map.of(work.group(),InstitutionWorkspaceScope.listed(Set.of(workspace))),0);
        var encoded=new InstitutionAssignmentCodec().encode(assignment);
        jdbc.update("insert into user_org_roles(user_id,org_id,role,permission_mode,permission_payload,permission_revision)"
                + " values(?,?,?::user_role,?,?::jsonb,nextval('institution_permission_revision_seq'))",id,org,assignment.displayRole().name(),encoded.mode(),encoded.payload());
        jdbc.update("update users set role=?::user_role where id=?",assignment.displayRole().name(),id);return id;
    }
    private long workspace(String name) { return jdbc.queryForObject("insert into workspaces(kind,name) values('PROJECT',?) returning id",Long.class,name+UUID.randomUUID()); }
    private UUID pub(String table,long id) { return SeedFixtures.publicId(jdbc,table,id); }
    private String bearer(long id) { return "Bearer "+tokens.createAccessToken(users.findById(id).orElseThrow()); }
    private String path() { return "/api/v1/admin/orgs/"+orgId+"/operations"; }
    private String payloads() { return jdbc.queryForList("select user_id,permission_mode,permission_payload::text,permission_revision,request_mail from user_org_roles where org_id=? order by user_id",org).toString(); }
    private long queued() { return jdbc.queryForObject("select count(*) from notifications",Long.class); }
}
