package kr.ac.pusan.pickle.vmrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import kr.ac.pusan.pickle.inventory.NodeRepository;
import kr.ac.pusan.pickle.inventory.TemplateStatus;
import kr.ac.pusan.pickle.inventory.VmTemplate;
import kr.ac.pusan.pickle.inventory.VmTemplateRepository;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
import kr.ac.pusan.pickle.vm.Vm;
import kr.ac.pusan.pickle.vm.VmRepository;
import kr.ac.pusan.pickle.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.hamcrest.Matchers;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * User VM request flow per contract: creation validation matrix (group
 * role, template rules, spec-reason rule, subdomain/domain rules), list/detail
 * visibility incl. the 403 non-member groupId filter, and cancel permissions
 * with the 409 double-decision guard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class VmRequestTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgRepository orgRepository;

    @Autowired
    private VmTemplateRepository templateRepository;

    @Autowired
    private NodeRepository nodeRepository;

    @Autowired
    private VmRepository vmRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User requester;
    private User editor;
    private User viewer;
    private User outsider;
    private String requesterToken;
    private String editorToken;
    private String viewerToken;
    private String outsiderToken;
    private Org org;
    private VmTemplate template;

    @BeforeEach
    void setUp() {
        requester = ensureUser("vmr.requester@pusan.ac.kr", "신청자");
        editor = ensureUser("vmr.manager@pusan.ac.kr", "매니저");
        viewer = ensureUser("vmr.viewer@pusan.ac.kr", "뷰어");
        outsider = ensureUser("vmr.outsider@pusan.ac.kr", "외부인");
        requesterToken = jwtService.createAccessToken(requester);
        editorToken = jwtService.createAccessToken(editor);
        viewerToken = jwtService.createAccessToken(viewer);
        outsiderToken = jwtService.createAccessToken(outsider);
        org = orgRepository.findBySlug(SeedFixtures.ORG_SLUG).orElseThrow();
        template = templateRepository.findAll().stream()
                .filter(t -> t.getName().equals("ubuntu-24.04") && t.getStatus() == TemplateStatus.ACTIVE)
                .findFirst().orElseThrow();
    }

    @Test
    void createValidatesRoleTemplateSpecAndDomains() throws Exception {
        long groupId = createTeam(requesterToken, "vmr-create-x1");
        addMember(requesterToken, groupId, viewer.getEmail(), "VIEWER");

        // OWNER submits with template defaults → 201 SUBMITTED, review null
        postJson("/api/v1/vm-requests", requesterToken, validBody(groupId))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.review").value((Object) null))
                .andExpect(jsonPath("$.groupId").value(groupId))
                .andExpect(jsonPath("$.groupName").isNotEmpty())
                .andExpect(jsonPath("$.orgName").isNotEmpty())
                .andExpect(jsonPath("$.requesterId").value(requester.getId()))
                .andExpect(jsonPath("$.requesterName").value("신청자"));

        // VIEWER / non-member cannot submit → 403 GROUP_ROLE_INSUFFICIENT
        postJson("/api/v1/vm-requests", viewerToken, validBody(groupId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));
        postJson("/api/v1/vm-requests", outsiderToken, validBody(groupId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GROUP_ROLE_INSUFFICIENT"));

        // unknown group / org / template → 404
        postJson("/api/v1/vm-requests", requesterToken, with(validBody(groupId), "groupId", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        postJson("/api/v1/vm-requests", requesterToken, with(validBody(groupId), "orgId", 999_999))
                .andExpect(status().isNotFound());
        postJson("/api/v1/vm-requests", requesterToken, with(validBody(groupId), "templateId", 999_999))
                .andExpect(status().isNotFound());

        // DISABLED template → 422
        VmTemplate disabled = templateRepository.save(new VmTemplate("vmr-disabled", "비활성 템플릿", 1002,
                nodeRepository.findAll().getFirst().getId(), 1, 2, 2048, 20, 10,
                TemplateStatus.DISABLED, null));
        postJson("/api/v1/vm-requests", requesterToken,
                with(validBody(groupId), "templateId", disabled.getId()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("templateId"));

        // below template minimum disk → 422
        postJson("/api/v1/vm-requests", requesterToken, with(validBody(groupId), "reqDiskGb", 5))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("reqDiskGb"));

        // spec above template defaults requires specReason (contract prose rule)
        postJson("/api/v1/vm-requests", requesterToken, with(validBody(groupId), "reqMemoryMb", 4096))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("specReason"));
        Map<String, Object> bigWithReason = with(validBody(groupId), "reqMemoryMb", 4096);
        bigWithReason.put("specReason", "동시 접속 부하 테스트를 위해 메모리 증설이 필요합니다.");
        postJson("/api/v1/vm-requests", requesterToken, bigWithReason)
                .andExpect(status().isCreated());

        // end date before start date → 422
        Map<String, Object> badDates = validBody(groupId);
        badDates.put("reqStartDate", "2026-08-01");
        badDates.put("reqEndDate", "2026-07-01");
        postJson("/api/v1/vm-requests", requesterToken, badDates)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("reqEndDate"));

        // needHttp implies subdomain + allowed root domain
        postJson("/api/v1/vm-requests", requesterToken, with(validBody(groupId), "needHttp", true))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[*].field").value(
                        Matchers.hasItems("desiredSubdomain", "rootDomain")));
        Map<String, Object> badRoot = httpBody(groupId, "vmr-svc-x1", "evil.example.com");
        postJson("/api/v1/vm-requests", requesterToken, badRoot)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("rootDomain"));

        // reserved subdomain → 422; malformed subdomain → 422 (bean validation)
        postJson("/api/v1/vm-requests", requesterToken, httpBody(groupId, "www", "pickle.pnuops.com"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("desiredSubdomain"));
        postJson("/api/v1/vm-requests", requesterToken, httpBody(groupId, "-bad-", "pickle.pnuops.com"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("desiredSubdomain"));

        // valid http publication request → 201
        String httpResponse = postJson("/api/v1/vm-requests", requesterToken,
                httpBody(groupId, "vmr-svc-x1", "pickle.pnuops.com"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.desiredSubdomain").value("vmr-svc-x1"))
                .andExpect(jsonPath("$.rootDomain").value("pickle.pnuops.com"))
                .andReturn().getResponse().getContentAsString();
        long httpRequestId = objectMapper.readTree(httpResponse).get("id").asLong();

        // duplicate (subdomain, rootDomain) held by a non-terminal request → 422
        postJson("/api/v1/vm-requests", requesterToken, httpBody(groupId, "vmr-svc-x1", "pickle.pnuops.com"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("desiredSubdomain"))
                .andExpect(jsonPath("$.errors[0].message").value("이미 사용 중이거나 신청된 서브도메인입니다."));

        // a terminal state (CANCELED) frees the pair for a new request
        postJson("/api/v1/vm-requests/" + httpRequestId + "/cancel", requesterToken, Map.of())
                .andExpect(status().isOk());
        postJson("/api/v1/vm-requests", requesterToken, httpBody(groupId, "vmr-svc-x1", "pickle.pnuops.com"))
                .andExpect(status().isCreated());

        // missing purpose → 422
        Map<String, Object> noPurpose = validBody(groupId);
        noPurpose.remove("purpose");
        postJson("/api/v1/vm-requests", requesterToken, noPurpose)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // submissions are audit-logged
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'request.create' and actor_id = ?",
                Long.class, requester.getId());
        assertThat(audits).isPositive();

        // unauthenticated → 401
        mockMvc.perform(get("/api/v1/vm-requests")).andExpect(status().isUnauthorized());
    }

    @Test
    void desiredSlugIsValidatedEchoedAndNeverRecycled() throws Exception {
        long groupId = createTeam(requesterToken, "vmr-slug-x1");

        // desiredSlug echoed in the detail; omitted → null
        String mine = postJson("/api/v1/vm-requests", requesterToken,
                with(validBody(groupId), "desiredSlug", "vmr-slug-mine"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.desiredSlug").value("vmr-slug-mine"))
                .andReturn().getResponse().getContentAsString();
        long mineId = objectMapper.readTree(mine).get("id").asLong();
        postJson("/api/v1/vm-requests", requesterToken, validBody(groupId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.desiredSlug").value((Object) null));

        // malformed slug → 422 (bean validation pattern)
        postJson("/api/v1/vm-requests", requesterToken, with(validBody(groupId), "desiredSlug", "-bad-"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("desiredSlug"));
        postJson("/api/v1/vm-requests", requesterToken, with(validBody(groupId), "desiredSlug", "ab"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("desiredSlug"));

        // reserved word (shared with reservedSubdomains) → 422
        postJson("/api/v1/vm-requests", requesterToken, with(validBody(groupId), "desiredSlug", "www"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("desiredSlug"));

        // another SUBMITTED request already asks for the slug → 422
        postJson("/api/v1/vm-requests", requesterToken,
                with(validBody(groupId), "desiredSlug", "vmr-slug-mine"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("desiredSlug"))
                .andExpect(jsonPath("$.errors[0].message").value("이미 신청 중인 호스트명입니다."));

        // an existing vms.hostname blocks the slug — even soft-deleted (never recycled)
        long vmReqId = submit(requesterToken, groupId);
        Vm vm = vmRepository.save(new Vm(nodeRepository.findAll().getFirst().getId(), groupId,
                org.getId(), vmReqId, "vmr-slug-taken", "vmr-slug-taken", template.getId(),
                1, 1024, 10, null, null));
        jdbcTemplate.update(
                "update vms set deleted_at = now(), status = 'DELETED'::vm_status where id = ?",
                vm.getId());
        postJson("/api/v1/vm-requests", requesterToken,
                with(validBody(groupId), "desiredSlug", "vmr-slug-taken"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("desiredSlug"))
                .andExpect(jsonPath("$.errors[0].message").value("이미 사용 중인 호스트명입니다."));

        // a rejected request is terminal — its desired slug is submittable again
        User orgAdmin = userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow();
        String orgAdminToken = jwtService.createAccessToken(orgAdmin);
        postJson("/api/v1/admin/vm-requests/" + mineId + "/reject", orgAdminToken,
                Map.of("comment", "슬러그 재사용 테스트를 위해 반려합니다."))
                .andExpect(status().isOk());
        postJson("/api/v1/vm-requests", requesterToken,
                with(validBody(groupId), "desiredSlug", "vmr-slug-mine"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.desiredSlug").value("vmr-slug-mine"));
    }

    @Test
    void listAndDetailVisibilityFollowsMembership() throws Exception {
        long groupId = createTeam(requesterToken, "vmr-visib-x1");
        addMember(requesterToken, groupId, viewer.getEmail(), "VIEWER");
        long first = submit(requesterToken, groupId);
        long second = submit(requesterToken, groupId);

        // group VIEWER sees the group's requests, newest first
        mockMvc.perform(get("/api/v1/vm-requests").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].id").value(second))
                .andExpect(jsonPath("$.content[1].id").value(first));

        // paging envelope: size=1 → two pages
        mockMvc.perform(get("/api/v1/vm-requests?size=1").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalPages").value(2));

        // invalid paging → 422
        mockMvc.perform(get("/api/v1/vm-requests?size=101").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // outsider sees nothing and cannot filter by this group
        mockMvc.perform(get("/api/v1/vm-requests").header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/vm-requests?groupId=" + groupId)
                        .header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // member groupId + status filters
        mockMvc.perform(get("/api/v1/vm-requests?groupId=" + groupId + "&status=SUBMITTED")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/vm-requests?groupId=" + groupId + "&status=CANCELED")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        // detail: participant ok, outsider 403, unknown id 404
        mockMvc.perform(get("/api/v1/vm-requests/" + first).header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(first))
                .andExpect(jsonPath("$.review").value((Object) null));
        mockMvc.perform(get("/api/v1/vm-requests/" + first).header("Authorization", "Bearer " + outsiderToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/vm-requests/999999").header("Authorization", "Bearer " + requesterToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void cancelIsForRequesterOrGroupManagersAndOnlyOnce() throws Exception {
        long groupId = createTeam(requesterToken, "vmr-cancel-x1");
        addMember(requesterToken, groupId, editor.getEmail(), "EDITOR");
        addMember(requesterToken, groupId, viewer.getEmail(), "VIEWER");
        long first = submit(requesterToken, groupId);
        long second = submit(requesterToken, groupId);

        // VIEWER and outsider cannot cancel → 403
        postJson("/api/v1/vm-requests/" + first + "/cancel", viewerToken, Map.of())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        postJson("/api/v1/vm-requests/" + first + "/cancel", outsiderToken, Map.of())
                .andExpect(status().isForbidden());

        // requester cancels own request → 200 CANCELED
        postJson("/api/v1/vm-requests/" + first + "/cancel", requesterToken, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        // second cancel → 409 REQUEST_ALREADY_DECIDED
        postJson("/api/v1/vm-requests/" + first + "/cancel", requesterToken, Map.of())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REQUEST_ALREADY_DECIDED"));

        // group EDITOR may cancel another member's request
        postJson("/api/v1/vm-requests/" + second + "/cancel", editorToken, Map.of())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));

        // unknown request → 404
        postJson("/api/v1/vm-requests/999999/cancel", requesterToken, Map.of())
                .andExpect(status().isNotFound());

        // cancellations are audit-logged
        Long audits = jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = 'request.cancel' and target_id in (?, ?)",
                Long.class, first, second);
        assertThat(audits).isEqualTo(2);
    }

    private Map<String, Object> validBody(long groupId) {
        Map<String, Object> body = new HashMap<>();
        body.put("groupId", groupId);
        body.put("orgId", org.getId());
        body.put("templateId", template.getId());
        body.put("purpose", "수업 실습용 서버");
        body.put("reqVcpu", template.getDefaultVcpu());
        body.put("reqMemoryMb", template.getDefaultMemoryMb());
        body.put("reqDiskGb", template.getDefaultDiskGb());
        body.put("needSsh", true);
        body.put("needHttp", false);
        body.put("needPublic", false);
        return body;
    }

    private Map<String, Object> httpBody(long groupId, String subdomain, String rootDomain) {
        Map<String, Object> body = validBody(groupId);
        body.put("needHttp", true);
        body.put("desiredSubdomain", subdomain);
        body.put("rootDomain", rootDomain);
        return body;
    }

    private static Map<String, Object> with(Map<String, Object> body, String key, Object value) {
        body.put(key, value);
        return body;
    }

    private long submit(String token, long groupId) throws Exception {
        String response = postJson("/api/v1/vm-requests", token, validBody(groupId))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("id").asLong();
    }

    private long createTeam(String token, String slug) throws Exception {
        String body = postJson("/api/v1/groups", token,
                Map.of("kind", "TEAM", "name", "테스트 그룹 " + slug, "slug", slug))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void addMember(String token, long groupId, String email, String role) throws Exception {
        postJson("/api/v1/groups/" + groupId + "/members", token, Map.of("email", email, "role", role))
                .andExpect(status().isCreated());
    }

    private ResultActions postJson(String uri, String token, Map<String, ?> body) throws Exception {
        return mockMvc.perform(post(uri)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private User ensureUser(String email, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
