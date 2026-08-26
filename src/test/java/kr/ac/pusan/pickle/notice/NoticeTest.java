package kr.ac.pusan.pickle.notice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.security.JwtService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 공지사항 per contract v0.48.0: the two visibility axes and the 404 mask that
 * enforces them, the active window, the organisation pinning on the write
 * paths, and the image rules.
 *
 * <p><b>Who counts as an organisation's reader.</b> Not whoever carries that
 * organisation in {@code users.org_id} — the schema gives that column to the
 * administrator and manager tiers only, so reading membership off the account
 * would hide an organisation's notices from every student it was written for.
 * Membership is the canonical derived rule instead, and
 * {@code anOrgNoticeReachesTheWorkspacesThatWorkUnderThatOrganisation} is the
 * test for exactly that: a regular account with no organisation column, made a
 * member of a workspace whose requests belong to the organisation, sees the
 * notice; one in an unrelated workspace does not.</p>
 *
 * <p>These reads are also the <b>only</b> coverage of the visibility widening.
 * The permission matrix records the three public operations as {@code public}
 * and stops there — it has no way to say that an anonymous caller gets less
 * back than an authenticated one.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class NoticeTest {

    /** A PNG is recognised by its 8-byte signature; the rest is never decoded. */
    private static final byte[] PNG_BYTES = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 'p', 'i', 'c', 'k', 'l', 'e'};
    private static final byte[] JPEG_BYTES = {
        (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F'};

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private OrgRepository orgRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Org org;
    private Org otherOrg;
    private String sysAdminToken;
    private String orgAdminToken;
    private String otherOrgAdminToken;
    private String userToken;

    private UUID platformPublic;
    private UUID platformUsers;
    private UUID ownOrgNotice;
    private UUID otherOrgNotice;

    @BeforeEach
    void setUp() throws Exception {
        org = orgRepository.findFirstByNameOrderByIdAsc(SeedFixtures.ORG_NAME).orElseThrow();
        otherOrg = orgRepository.findFirstByNameOrderByIdAsc("공지 타기관").orElseGet(() ->
                orgRepository.save(new Org("공지 타기관", null)));
        User otherOrgAdmin = ensureAdmin("notice.other.admin@pusan.ac.kr", "공지타기관장",
                otherOrg.getId());
        User reader = ensureRegularUser("notice.reader@pusan.ac.kr", "공지독자");

        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
        otherOrgAdminToken = jwtService.createAccessToken(otherOrgAdmin);
        userToken = jwtService.createAccessToken(reader);

        // Images cascade with their notice, so one delete clears both tables.
        jdbcTemplate.update("delete from notices");

        platformPublic = createdId(create(sysAdminToken, body(Map.of(
                "title", "전체 공개 공지", "scope", "PLATFORM", "audience", "PUBLIC"))));
        platformUsers = createdId(create(sysAdminToken, body(Map.of(
                "title", "로그인 전용 전역 공지", "scope", "PLATFORM", "audience", "USERS"))));
        // Both roles name the organisation explicitly; the console does the same.
        ownOrgNotice = createdId(create(orgAdminToken, body(Map.of(
                "title", "자기관 공지", "scope", "ORG", "audience", "USERS",
                "orgId", org.getPublicId().toString()))));
        otherOrgNotice = createdId(create(otherOrgAdminToken, body(Map.of(
                "title", "타기관 공지", "scope", "ORG", "audience", "USERS",
                "orgId", otherOrg.getPublicId().toString()))));
    }

    @Test
    void anonymousReaderSeesOnlyPublicPlatformNotices() throws Exception {
        publicList(null)
                .andExpect(status().isOk())
                .andExpect(listHas(platformPublic))
                .andExpect(listOmits(platformUsers))
                .andExpect(listOmits(ownOrgNotice))
                .andExpect(listOmits(otherOrgNotice));

        // A notice an anonymous caller may not see is absent, not refused: a 403
        // would confirm that this identifier names a real notice.
        publicGet(null, platformPublic).andExpect(status().isOk());
        publicGet(null, platformUsers)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        publicGet(null, ownOrgNotice).andExpect(status().isNotFound());
    }

    @Test
    void signedInReaderSeesOwnOrgButNeverAnother() throws Exception {
        publicList(orgAdminToken)
                .andExpect(status().isOk())
                .andExpect(listHas(platformPublic))
                .andExpect(listHas(platformUsers))
                .andExpect(listHas(ownOrgNotice))
                .andExpect(listOmits(otherOrgNotice));

        publicGet(orgAdminToken, ownOrgNotice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgId").value(org.getPublicId().toString()));
        // The other organisation's notice answers exactly as a nonexistent one.
        publicGet(orgAdminToken, otherOrgNotice).andExpect(status().isNotFound());
        publicGet(orgAdminToken, SeedFixtures.UNKNOWN_ID).andExpect(status().isNotFound());

        // An account carrying no organisation at all is the ordinary case, and
        // the query must answer it rather than fault on the null: it gets both
        // platform notices and neither organisation's.
        publicList(userToken)
                .andExpect(status().isOk())
                .andExpect(listHas(platformPublic))
                .andExpect(listHas(platformUsers))
                .andExpect(listOmits(ownOrgNotice))
                .andExpect(listOmits(otherOrgNotice));
        publicGet(userToken, platformUsers).andExpect(status().isOk());
        publicGet(userToken, ownOrgNotice).andExpect(status().isNotFound());
    }

    @Test
    void theActiveWindowFiltersThePublicListButNotTheAdminOne() throws Exception {
        Instant now = Instant.now();
        UUID scheduled = createdId(create(sysAdminToken, body(Map.of(
                "title", "예정 공지", "scope", "PLATFORM", "audience", "PUBLIC",
                "startsAt", now.plus(1, ChronoUnit.DAYS).toString()))));
        UUID expired = createdId(create(sysAdminToken, body(Map.of(
                "title", "만료 공지", "scope", "PLATFORM", "audience", "PUBLIC",
                "startsAt", now.minus(2, ChronoUnit.DAYS).toString(),
                "endsAt", now.minus(1, ChronoUnit.DAYS).toString()))));

        publicList(null)
                .andExpect(listHas(platformPublic))
                .andExpect(listOmits(scheduled))
                .andExpect(listOmits(expired));
        publicGet(null, scheduled).andExpect(status().isNotFound());
        publicGet(null, expired).andExpect(status().isNotFound());

        // The admin list is the whole surface, each row saying where it stands.
        adminList(sysAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath(rowWhere(scheduled, "@.active==false")).exists())
                .andExpect(jsonPath(rowWhere(expired, "@.active==false")).exists())
                .andExpect(jsonPath(rowWhere(platformPublic, "@.active==true")).exists())
                // list rows carry the body, which is why there is no admin detail read
                .andExpect(jsonPath(rowWhere(platformPublic, "@.body")).exists());

        // A window that closes before it opens is refused here rather than left
        // to notices_window_check, which would surface as a 500.
        create(sysAdminToken, body(Map.of(
                "title", "거꾸로", "scope", "PLATFORM", "audience", "PUBLIC",
                "startsAt", now.toString(),
                "endsAt", now.minus(1, ChronoUnit.HOURS).toString())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='endsAt')]").exists());
    }

    @Test
    void anOmittedEditFieldKeepsItsValueAndAnExplicitNullClearsTheExpiry() throws Exception {
        Instant endsAt = Instant.now().plus(7, ChronoUnit.DAYS);
        UUID expiring = createdId(create(sysAdminToken, body(Map.of(
                "title", "기간 있는 공지", "scope", "PLATFORM", "audience", "PUBLIC",
                "endsAt", endsAt.toString()))));

        // Omitted: the expiry survives an edit that says nothing about it.
        patchNotice(sysAdminToken, expiring, Map.of("title", "기간 있는 공지(수정)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("기간 있는 공지(수정)"))
                .andExpect(jsonPath("$.endsAt").isNotEmpty());

        // Explicit null: the notice stops expiring. These two are the same
        // absent value in a plain record, which is why the body is presence-tracked.
        Map<String, Object> clearing = new HashMap<>();
        clearing.put("endsAt", null);
        patchNotice(sysAdminToken, expiring, clearing)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endsAt").doesNotExist());

        // An edit that changes nothing at all is refused rather than silently
        // accepted, so the console cannot mistake a no-op for a save.
        patchNotice(sysAdminToken, expiring, Map.of())
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void anOrgAdminIsPinnedToItsOwnOrganisation() throws Exception {
        // A platform notice is not theirs to publish.
        create(orgAdminToken, body(Map.of(
                "title", "전역 시도", "scope", "PLATFORM", "audience", "PUBLIC")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        // Nor is another organisation's. Refused, not quietly rewritten to
        // theirs: an attempted cross-org write is the event worth surfacing.
        create(orgAdminToken, body(Map.of(
                "title", "타기관 시도", "scope", "ORG", "audience", "USERS",
                "orgId", otherOrg.getPublicId().toString())))
                .andExpect(status().isForbidden());

        // But naming their OWN organisation is accepted, not refused as a field
        // they may not set. The console sends it for every role, so folding this
        // into the 403 branch would reject a write the user cannot even see the
        // input for.
        create(orgAdminToken, body(Map.of(
                "title", "자기 기관 명시", "scope", "ORG", "audience", "USERS",
                "orgId", org.getPublicId().toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orgId").value(org.getPublicId().toString()))
                .andExpect(jsonPath("$.orgName").value(org.getName()));

        // And an organisation notice always names one.
        create(orgAdminToken, body(Map.of(
                "title", "기관 미지정", "scope", "ORG", "audience", "USERS")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='orgId')]").exists());

        // Another organisation's notice is masked, exactly as it is on the read.
        patchNotice(orgAdminToken, otherOrgNotice, Map.of("title", "가로채기"))
                .andExpect(status().isNotFound());
        deleteNotice(orgAdminToken, otherOrgNotice).andExpect(status().isNotFound());
        upload(orgAdminToken, otherOrgNotice, "a.png", "image/png", PNG_BYTES)
                .andExpect(status().isNotFound());

        // A platform notice is refused rather than masked: they see it in their
        // own admin list, so pretending it is absent would contradict that list.
        patchNotice(orgAdminToken, platformPublic, Map.of("title", "전역 수정"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Their own notice edits normally.
        patchNotice(orgAdminToken, ownOrgNotice, Map.of("title", "자기관 공지(수정)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("자기관 공지(수정)"));

        // Their admin list carries the platform notices and never another org's.
        adminList(orgAdminToken)
                .andExpect(status().isOk())
                .andExpect(listHas(platformPublic))
                .andExpect(listHas(ownOrgNotice))
                .andExpect(listOmits(otherOrgNotice));

        // A regular account never reaches the surface at all.
        adminList(userToken).andExpect(status().isForbidden());
    }

    @Test
    void anOrgNoticeCanNeverBePublic() throws Exception {
        create(sysAdminToken, body(Map.of(
                "title", "기관 공개 시도", "scope", "ORG", "audience", "PUBLIC",
                "orgId", org.getPublicId().toString())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field=='audience')]").exists());

        // And the same refusal on the way in through an edit.
        patchNotice(orgAdminToken, ownOrgNotice, Map.of("audience", "PUBLIC"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='audience')]").exists());
    }

    @Test
    void imageUploadsAreJudgedByTheirBytesAndCapped() throws Exception {
        // A text payload wearing image/png is refused: the declared type is a
        // claim, and the leading bytes are what decide.
        upload(sysAdminToken, platformPublic, "fake.png", "image/png",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOTICE_IMAGE_TYPE_UNSUPPORTED"));

        byte[] oversized = new byte[NoticeImageTypes.MAX_BYTES + 1];
        System.arraycopy(PNG_BYTES, 0, oversized, 0, PNG_BYTES.length);
        upload(sysAdminToken, platformPublic, "big.png", "image/png", oversized)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("NOTICE_IMAGE_TOO_LARGE"));

        // The stored type is the real one, not the one the upload declared.
        upload(sysAdminToken, platformPublic, "photo.png", "image/png", JPEG_BYTES)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.byteSize").value(JPEG_BYTES.length));

        // Four more fill the notice; the one after that is refused.
        for (int i = 2; i <= NoticeImageTypes.MAX_PER_NOTICE; i++) {
            upload(sysAdminToken, platformPublic, "img" + i + ".png", "image/png", PNG_BYTES)
                    .andExpect(status().isCreated());
        }
        upload(sysAdminToken, platformPublic, "one-too-many.png", "image/png", PNG_BYTES)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTICE_IMAGE_LIMIT_EXCEEDED"));
    }

    @Test
    void servingAnImageReturnsTheStoredBytesAndInheritsTheNoticesVisibility() throws Exception {
        JsonNode uploaded = json(upload(sysAdminToken, platformPublic, "hero.png", "image/png",
                PNG_BYTES).andExpect(status().isCreated()).andReturn());
        UUID imageId = UUID.fromString(uploaded.get("id").asString());
        String url = uploaded.get("url").asString();
        // The finished string, not just the ids behind it: this is a
        // concatenation site, and nothing downstream would object to a wrong one.
        assertThat(url).isEqualTo("/api/v1/notices/" + platformPublic + "/images/" + imageId);

        MvcResult served = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "public, max-age=31536000, immutable"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn();
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(PNG_BYTES);

        // The notice's own visibility governs its images.
        JsonNode orgImage = json(upload(orgAdminToken, ownOrgNotice, "org.png", "image/png",
                PNG_BYTES).andExpect(status().isCreated()).andReturn());
        String orgUrl = orgImage.get("url").asString();
        mockMvc.perform(get(orgUrl)).andExpect(status().isNotFound());
        mockMvc.perform(get(orgUrl).header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());

        // An image reached through a notice that does not own it is absent too,
        // even when that notice is one the caller may read.
        mockMvc.perform(get("/api/v1/notices/" + platformPublic + "/images/"
                        + orgImage.get("id").asString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingANoticeTakesItsImagesAndLeavesAnAuditTrail() throws Exception {
        upload(sysAdminToken, platformPublic, "gone.png", "image/png", PNG_BYTES)
                .andExpect(status().isCreated());
        long noticeId = SeedFixtures.internalId(jdbcTemplate, "notices", platformPublic);
        assertThat(imageRows(noticeId)).isEqualTo(1);

        deleteNotice(sysAdminToken, platformPublic).andExpect(status().isNoContent());
        assertThat(imageRows(noticeId)).isZero();
        publicGet(null, platformPublic).andExpect(status().isNotFound());

        assertThat(auditRows("notice.create", ownOrgNotice)).isEqualTo(1);
        assertThat(auditRows("notice.image_add", platformPublic)).isEqualTo(1);
        assertThat(auditRows("notice.delete", platformPublic)).isEqualTo(1);
    }

    @Test
    void anOrgNoticeReachesTheWorkspacesThatWorkUnderThatOrganisation() throws Exception {
        // A regular account carries no users.org_id at all — the schema gives
        // that column only to the administrator and manager tiers. Membership is
        // derived instead: this reader is an ACTIVE member of a workspace whose
        // requests belong to the organisation, which is what makes them "이 기관
        // 사람" for the announcement fan-out and must mean the same here.
        User member = ensureRegularUser("notice.member@pusan.ac.kr", "공지기관원");
        assertThat(member.getOrgId()).as("a regular account has no org column").isNull();
        long linkedWorkspace = createWorkspace("noticelink", member.getId());
        linkWorkspaceToOrg(linkedWorkspace, org.getId(), member.getId());
        String memberToken = jwtService.createAccessToken(member);

        publicList(memberToken)
                .andExpect(status().isOk())
                .andExpect(listHas(platformPublic))
                .andExpect(listHas(platformUsers))
                .andExpect(listHas(ownOrgNotice))
                .andExpect(listOmits(otherOrgNotice));
        publicGet(memberToken, ownOrgNotice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orgName").value(org.getName()));
        publicGet(memberToken, otherOrgNotice).andExpect(status().isNotFound());

        // A reader whose workspace has nothing to do with either organisation
        // derives no membership and sees only the platform's notices.
        User stranger = ensureRegularUser("notice.stranger@pusan.ac.kr", "무관워크스페이스원");
        createWorkspace("noticefree", stranger.getId());
        String strangerToken = jwtService.createAccessToken(stranger);
        publicList(strangerToken)
                .andExpect(status().isOk())
                .andExpect(listHas(platformUsers))
                .andExpect(listOmits(ownOrgNotice))
                .andExpect(listOmits(otherOrgNotice));
        // The 404 mask uses the same resolved set as the list, so a notice this
        // reader cannot list is not fetchable by id either.
        publicGet(strangerToken, ownOrgNotice).andExpect(status().isNotFound());
    }

    @Test
    void aNoticeCannotBePromotedOrMovedAfterItIsCreated() throws Exception {
        // The create gate would be worth nothing if the object were mutable
        // through a second verb, so the edit body carries neither field and an
        // attempt to send them changes nothing.
        Map<String, Object> promote = new HashMap<>();
        promote.put("scope", "PLATFORM");
        promote.put("orgId", otherOrg.getPublicId().toString());
        promote.put("title", "승격 시도");
        patchNotice(orgAdminToken, ownOrgNotice, promote)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("승격 시도"))
                .andExpect(jsonPath("$.scope").value("ORG"))
                .andExpect(jsonPath("$.orgId").value(org.getPublicId().toString()));

        // Same from the system tier: the fields are not part of the contract,
        // so nobody has a promote path.
        patchNotice(sysAdminToken, ownOrgNotice, promote)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("ORG"))
                .andExpect(jsonPath("$.orgId").value(org.getPublicId().toString()));

        // And the notice stays invisible to the other organisation throughout.
        publicGet(otherOrgAdminToken, ownOrgNotice).andExpect(status().isNotFound());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private ResultActions publicList(String token) throws Exception {
        return mockMvc.perform(authorize(get("/api/v1/notices?size=100"), token));
    }

    private ResultActions publicGet(String token, UUID noticeId) throws Exception {
        return mockMvc.perform(authorize(get("/api/v1/notices/" + noticeId), token));
    }

    private ResultActions adminList(String token) throws Exception {
        return mockMvc.perform(authorize(get("/api/v1/admin/notices?size=100"), token));
    }

    private ResultActions create(String token, Map<String, Object> requestBody) throws Exception {
        return mockMvc.perform(authorize(post("/api/v1/admin/notices"), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody)));
    }

    private ResultActions patchNotice(String token, UUID noticeId, Map<String, Object> requestBody)
            throws Exception {
        return mockMvc.perform(authorize(patch("/api/v1/admin/notices/" + noticeId), token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody)));
    }

    private ResultActions deleteNotice(String token, UUID noticeId) throws Exception {
        return mockMvc.perform(authorize(delete("/api/v1/admin/notices/" + noticeId), token));
    }

    private ResultActions upload(String token, UUID noticeId, String fileName, String declaredType,
            byte[] bytes) throws Exception {
        return mockMvc.perform(multipart("/api/v1/admin/notices/" + noticeId + "/images")
                .file(new MockMultipartFile("file", fileName, declaredType, bytes))
                .header("Authorization", "Bearer " + token));
    }

    private static MockHttpServletRequestBuilder authorize(MockHttpServletRequestBuilder request,
            String token) {
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    /** A create body with the field every notice needs already filled in. */
    private static Map<String, Object> body(Map<String, Object> fields) {
        Map<String, Object> requestBody = new HashMap<>(fields);
        requestBody.putIfAbsent("body", "본문입니다.");
        return requestBody;
    }

    private UUID createdId(ResultActions result) throws Exception {
        result.andExpect(status().isCreated());
        return UUID.fromString(json(result.andReturn()).get("id").asString());
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** The page row for one notice, narrowed by a further JSONPath predicate. */
    private static String rowWhere(UUID noticeId, String predicate) {
        return "$.content[?(@.id=='" + noticeId + "' && " + predicate + ")]";
    }

    private static ResultMatcher listHas(UUID noticeId) {
        return jsonPath("$.content[?(@.id=='" + noticeId + "')]").exists();
    }

    private static ResultMatcher listOmits(UUID noticeId) {
        return jsonPath("$.content[?(@.id=='" + noticeId + "')]").doesNotExist();
    }

    private long imageRows(long noticeId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from notice_images where notice_id = ?", Long.class, noticeId);
    }

    private long auditRows(String action, UUID targetId) {
        return jdbcTemplate.queryForObject("""
                select count(*) from audit_logs where action = ? and target_id = ?
                """, Long.class, action, targetId.toString());
    }

    private long createWorkspace(String prefix, long... memberIds) {
        String name = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        long workspaceId = jdbcTemplate.queryForObject("""
                insert into workspaces (kind, name) values ('TEAM', ?) returning id
                """, Long.class, name);
        for (long memberId : memberIds) {
            jdbcTemplate.update("""
                    insert into workspace_members (workspace_id, user_id, role) values (?, ?, 'MEMBER')
                    """, workspaceId, memberId);
        }
        return workspaceId;
    }

    /** Derived-membership link: one request of the workspace in the org. */
    private void linkWorkspaceToOrg(long workspaceId, long orgId, long requesterId) {
        RequestFixtures.insertVmRequest(jdbcTemplate, workspaceId, orgId, requesterId,
                "조직 연계(테스트)", null, 1, 1024, 20);
    }

    private User ensureAdmin(String email, String name, Long orgId) {
        User user = userRepository.findByEmail(email).orElseGet(() ->
                userRepository.save(new User(email, "{noop}unused", name)));
        user.setRole(UserRole.ORG_ADMIN);
        user.setOrgId(orgId);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private User ensureRegularUser(String email, String name) {
        User user = userRepository.findByEmail(email).orElseGet(() ->
                userRepository.save(new User(email, "{noop}unused", name)));
        user.setRole(UserRole.USER);
        user.setOrgId(null);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }
}
