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
 * 공지사항 per contract v0.49.0: the two visibility axes and the 404 mask that
 * enforces them, the active window, the organisation pinning on the write
 * paths, and the image rules.
 *
 * <p><b>Who counts as an organisation's reader.</b> Not whoever is granted a
 * role in that organisation — {@code user_org_roles} carries the org tier only,
 * so reading membership off the account would hide an organisation's notices
 * from every student it was written for.
 * Membership is the canonical derived rule instead, and
 * {@code anOrgNoticeReachesTheWorkspacesThatWorkUnderThatOrganisation} is the
 * test for exactly that: a regular account granted no organisation, made a
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
        // A field error rather than a refusal, because orgId is a submitted
        // value and no existing row's privacy is at stake — the same answer
        // AnnouncementService gives the same shape.
        create(orgAdminToken, body(Map.of(
                "title", "타기관 시도", "scope", "ORG", "audience", "USERS",
                "orgId", otherOrg.getPublicId().toString())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='orgId')]").exists());

        // But naming their OWN organisation is accepted, not refused as a field
        // they may not set. The console sends it for every role, so folding this
        // into the 422 above would reject a write the user cannot even see the
        // input for.
        create(orgAdminToken, body(Map.of(
                "title", "자기 기관 명시", "scope", "ORG", "audience", "USERS",
                "orgId", org.getPublicId().toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orgId").value(org.getPublicId().toString()))
                .andExpect(jsonPath("$.orgName").value(org.getName()));

        // Omitting it is fine for an account that administers exactly one
        // organisation: there is only one answer, so the server supplies it.
        create(orgAdminToken, body(Map.of(
                "title", "기관 미지정", "scope", "ORG", "audience", "USERS")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orgId").value(org.getPublicId().toString()));

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
    void aViewerReadsTheManagementListAndIsRefusedEveryWrite() throws Exception {
        // A viewer holds whatever read access its tier's operator holds, so the
        // management list is in scope. The audit log is the one surface that
        // argues otherwise, and only for the ORG viewer — its rows carry login
        // addresses, so inside the org tier it stays with the roles that may
        // act, while SYS_VIEWER reads it like any other system read. A notice
        // has no equivalent, so nothing here argues for keeping either out.
        User orgViewer = ensureOrgUser("notice.org.viewer@pusan.ac.kr", "공지기관열람자",
                org.getId(), UserRole.ORG_VIEWER);
        String orgViewerToken = jwtService.createAccessToken(orgViewer);

        // Scoped exactly as that organisation's admin sees it: the platform's
        // notices as read-only rows, its own organisation's, never another's.
        adminList(orgViewerToken)
                .andExpect(status().isOk())
                .andExpect(listHas(platformPublic))
                .andExpect(listHas(ownOrgNotice))
                .andExpect(listOmits(otherOrgNotice));

        // Every write is refused by the method's own gate, which fully replaces
        // the widened class-level one — ACCESS_DENIED rather than any other 403,
        // so a refusal from somewhere else in the chain cannot pass for this.
        create(orgViewerToken, body(Map.of(
                "title", "열람자 등록 시도", "scope", "ORG", "audience", "USERS",
                "orgId", org.getPublicId().toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        patchNotice(orgViewerToken, ownOrgNotice, Map.of("title", "열람자 수정 시도"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        deleteNotice(orgViewerToken, ownOrgNotice)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        upload(orgViewerToken, ownOrgNotice, "a.png", "image/png", PNG_BYTES)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // The system viewer is not scoped to an organisation, so it reads every
        // organisation's notices — and writes none of them.
        User sysViewer = ensureUnattachedUser("notice.sys.viewer@pusan.ac.kr", "공지전체열람자",
                UserRole.SYS_VIEWER);
        String sysViewerToken = jwtService.createAccessToken(sysViewer);

        adminList(sysViewerToken)
                .andExpect(status().isOk())
                .andExpect(listHas(platformPublic))
                .andExpect(listHas(ownOrgNotice))
                .andExpect(listHas(otherOrgNotice));

        create(sysViewerToken, body(Map.of(
                "title", "전체열람자 등록 시도", "scope", "PLATFORM", "audience", "PUBLIC")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        patchNotice(sysViewerToken, platformPublic, Map.of("title", "전체열람자 수정 시도"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        deleteNotice(sysViewerToken, ownOrgNotice)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // Nothing the refused writes attempted actually landed.
        adminList(sysAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath(rowWhere(ownOrgNotice, "@.title=='자기관 공지'")).exists())
                .andExpect(listHas(platformPublic));
    }

    @Test
    void administeringOneOrgNeverConfersAWriteInAnotherItOnlyReads() throws Exception {
        // Since V90 an account can administer one organisation and only read a
        // second, and users.role is the HIGHEST role it holds anywhere — so the
        // @PreAuthorize gate on the writes sees ORG_ADMIN and lets this account
        // through. What keeps it out of the organisation it merely reads is the
        // service asking administers(thatOrg), and this is the test for it.
        User dualRole = ensureOrgUser("notice.dual@pusan.ac.kr", "겸직관리자",
                org.getId(), UserRole.ORG_ADMIN);
        SeedFixtures.grantOrgRole(jdbcTemplate, dualRole.getId(), otherOrg.getId(),
                UserRole.ORG_VIEWER);
        String dualToken = jwtService.createAccessToken(dualRole);

        // The management list is the wider, readable scope: both organisations.
        adminList(dualToken)
                .andExpect(status().isOk())
                .andExpect(listHas(ownOrgNotice))
                .andExpect(listHas(otherOrgNotice));

        // The organisation it administers writes normally.
        patchNotice(dualToken, ownOrgNotice, Map.of("title", "겸직 수정"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("겸직 수정"));

        // The one it only reads is masked on every write, exactly as an
        // organisation it holds nothing in would be.
        patchNotice(dualToken, otherOrgNotice, Map.of("title", "겸직 가로채기"))
                .andExpect(status().isNotFound());
        deleteNotice(dualToken, otherOrgNotice).andExpect(status().isNotFound());
        upload(dualToken, otherOrgNotice, "a.png", "image/png", PNG_BYTES)
                .andExpect(status().isNotFound());

        // And it cannot create one there either — refused, not silently pinned
        // to the organisation it does administer.
        create(dualToken, body(Map.of(
                "title", "겸직 등록 시도", "scope", "ORG", "audience", "USERS",
                "orgId", otherOrg.getPublicId().toString())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='orgId')]").exists());

        // Nothing landed in the organisation it only reads.
        adminList(sysAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath(rowWhere(otherOrgNotice, "@.title=='타기관 공지'")).exists());
    }

    @Test
    void theCreateTargetFollowsTheSameRulesAnAnnouncementDoes() throws Exception {
        // An account administering TWO organisations has no single "own org",
        // so naming one becomes required from the second onwards.
        User twoOrgAdmin = ensureOrgUser("notice.two.admin@pusan.ac.kr", "겸임기관장",
                org.getId(), UserRole.ORG_ADMIN);
        SeedFixtures.grantOrgRole(jdbcTemplate, twoOrgAdmin.getId(), otherOrg.getId(),
                UserRole.ORG_ADMIN);
        String twoOrgToken = jwtService.createAccessToken(twoOrgAdmin);

        create(twoOrgToken, body(Map.of(
                "title", "겸임 기관 미지정", "scope", "ORG", "audience", "USERS")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='orgId')]").exists());

        // Naming either of the two is accepted — both are theirs.
        create(twoOrgToken, body(Map.of(
                "title", "겸임 1", "scope", "ORG", "audience", "USERS",
                "orgId", org.getPublicId().toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orgId").value(org.getPublicId().toString()));
        create(twoOrgToken, body(Map.of(
                "title", "겸임 2", "scope", "ORG", "audience", "USERS",
                "orgId", otherOrg.getPublicId().toString())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orgId").value(otherOrg.getPublicId().toString()));

        // An organisation the account holds only a NON-administering role in is
        // not a target: reading or operating there is not the right to publish.
        User managerElsewhere = ensureOrgUser("notice.mgr@pusan.ac.kr", "타기관운영자",
                org.getId(), UserRole.ORG_ADMIN);
        SeedFixtures.grantOrgRole(jdbcTemplate, managerElsewhere.getId(), otherOrg.getId(),
                UserRole.ORG_MANAGER);
        String managerToken = jwtService.createAccessToken(managerElsewhere);
        create(managerToken, body(Map.of(
                "title", "운영 기관 시도", "scope", "ORG", "audience", "USERS",
                "orgId", otherOrg.getPublicId().toString())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='orgId')]").exists());

        // An organisation that does not exist answers exactly as one they do
        // not administer, so which organisations exist stays private from the
        // org tier. The sys tier, which may target any of them, gets the 404.
        create(orgAdminToken, body(Map.of(
                "title", "없는 기관", "scope", "ORG", "audience", "USERS",
                "orgId", SeedFixtures.UNKNOWN_ID.toString())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='orgId')]").exists());
        create(sysAdminToken, body(Map.of(
                "title", "없는 기관(시스템)", "scope", "ORG", "audience", "USERS",
                "orgId", SeedFixtures.UNKNOWN_ID.toString())))
                .andExpect(status().isNotFound());

        // The sys tier administers nothing, so it can never omit the field.
        create(sysAdminToken, body(Map.of(
                "title", "시스템 기관 미지정", "scope", "ORG", "audience", "USERS")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='orgId')]").exists());
    }

    @Test
    void aViewerLooksInThroughManagementAndNotThroughTheBoard() throws Exception {
        // An ORG notice is addressed to that organisation's people. A viewer is
        // an outsider permitted to look in, so it reaches the notice through the
        // management surface, which exists for looking in, and not through the
        // board, where an organisation's own people read what was addressed to
        // them. The announcement fan-out draws the same line.
        User orgViewer = ensureOrgUser("notice.board.viewer@pusan.ac.kr", "게시판열람자",
                org.getId(), UserRole.ORG_VIEWER);
        String viewerToken = jwtService.createAccessToken(orgViewer);

        // Not on the board, and the notice answers as one that does not exist.
        publicList(viewerToken)
                .andExpect(status().isOk())
                .andExpect(listHas(platformPublic))
                .andExpect(listHas(platformUsers))
                .andExpect(listOmits(ownOrgNotice));
        publicGet(viewerToken, ownOrgNotice).andExpect(status().isNotFound());

        // But present on the management surface, which is the pair that pins the
        // distinction: this is about which definition of "이 기관 사람" governs
        // the board, not about withholding the notice from them.
        adminList(viewerToken)
                .andExpect(status().isOk())
                .andExpect(listHas(ownOrgNotice));

        // An operating role in the same organisation does reach the board: they
        // are the organisation's own people, not outsiders looking in.
        User orgManager = ensureOrgUser("notice.board.mgr@pusan.ac.kr", "게시판운영자",
                org.getId(), UserRole.ORG_MANAGER);
        publicList(jwtService.createAccessToken(orgManager))
                .andExpect(status().isOk())
                .andExpect(listHas(ownOrgNotice));
    }

    @Test
    void everyWriteRefusalUsesTheCodeItsIdentifierCallsFor() throws Exception {
        // The rule is where the identifier is, not which verb it is: a notice
        // named by PATH stays private behind a 404, an orgId named in the BODY
        // is a field error, and an actor with no organisation at all has no
        // resource to mask so it is simply refused.

        // All four path-addressed writes mask. The image case uses an image
        // that really exists on the other organisation's notice, so the 404 can
        // only be the scope mask and not a missing image answering for it.
        UUID foreignImage = UUID.fromString(json(upload(otherOrgAdminToken, otherOrgNotice,
                "foreign.png", "image/png", PNG_BYTES).andExpect(status().isCreated())
                .andReturn()).get("id").asString());
        patchNotice(orgAdminToken, otherOrgNotice, Map.of("title", "가로채기"))
                .andExpect(status().isNotFound());
        deleteNotice(orgAdminToken, otherOrgNotice).andExpect(status().isNotFound());
        upload(orgAdminToken, otherOrgNotice, "a.png", "image/png", PNG_BYTES)
                .andExpect(status().isNotFound());
        deleteAdminImage(orgAdminToken, otherOrgNotice, foreignImage)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        // The image is still there: the refusal did not half-apply.
        deleteAdminImage(otherOrgAdminToken, otherOrgNotice, foreignImage)
                .andExpect(status().isNoContent());

        // An account holding ORG_ADMIN but granted no organisation reaches the
        // gates — users.role is what they see — and is refused at the service.
        // 403, not 404: there is no resource here whose existence a mask would
        // be protecting.
        User unattached = ensureUnattachedUser("notice.unattached@pusan.ac.kr", "무소속관리자",
                UserRole.ORG_ADMIN);
        String unattachedToken = jwtService.createAccessToken(unattached);
        adminList(unattachedToken)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        create(unattachedToken, body(Map.of(
                "title", "무소속 등록 시도", "scope", "ORG", "audience", "USERS",
                "orgId", org.getPublicId().toString())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
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

        // Anonymously readable, so a cache shared between users may keep it —
        // but only for an hour, and without the promise never to revalidate.
        // The bytes behind this URL never change; what can change is whether
        // this caller is still entitled to them, and a shared cache is the only
        // one that could then serve them to somebody who never was.
        MvcResult served = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        "public, max-age=31536000, s-maxage=3600"))
                .andExpect(header().string("Content-Disposition", "inline"))
                .andExpect(header().string("Content-Type", "image/png"))
                .andReturn();
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(PNG_BYTES);

        // The notice's own visibility governs its images.
        JsonNode orgImage = json(upload(orgAdminToken, ownOrgNotice, "org.png", "image/png",
                PNG_BYTES).andExpect(status().isCreated()).andReturn());
        String orgUrl = orgImage.get("url").asString();
        mockMvc.perform(get(orgUrl)).andExpect(status().isNotFound());
        // Whose answer this is depends on who asked, and the response carries no
        // Vary that would tell two callers apart — so it must never be storable
        // in a cache shared between them. private still caches in the
        // requester's own browser, which is all this ever needed, and keeps the
        // year: re-serving bytes to the one caller who already received them is
        // indistinguishable from their having saved the file.
        mockMvc.perform(get(orgUrl).header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        "private, max-age=31536000, immutable"));

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
        // A regular account is granted no organisation at all — user_org_roles
        // carries the org tier only. Membership is derived instead: this reader
        // is an ACTIVE member of a workspace whose requests belong to the
        // organisation, which is what makes them "이 기관 사람" for the
        // announcement fan-out and must mean the same here.
        User member = ensureRegularUser("notice.member@pusan.ac.kr", "공지기관원");
        assertThat(orgRoleRows(member.getId()))
                .as("a regular account holds no organisation role").isZero();
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

        // Suspending the account takes the organisation's notices away again.
        // On a permitAll path an unusable token is not a 401 — the filter simply
        // builds no principal — so the caller degrades to anonymous and sees
        // less, never more.
        member.setStatus(UserStatus.DISABLED);
        userRepository.save(member);
        publicList(memberToken)
                .andExpect(status().isOk())
                .andExpect(listHas(platformPublic))
                .andExpect(listOmits(platformUsers))
                .andExpect(listOmits(ownOrgNotice));
        publicGet(memberToken, ownOrgNotice).andExpect(status().isNotFound());
        member.setStatus(UserStatus.ACTIVE);
        userRepository.save(member);

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

    @Test
    void managementCanSeeTheImagesOfANoticeThatIsNotPublishedYet() throws Exception {
        // The management screen shows a scheduled notice before it goes live,
        // and its list row carries the image URLs. Sending those through the
        // public window check alone would 404 every one of them.
        UUID scheduled = createdId(create(sysAdminToken, body(Map.of(
                "title", "예정 공지(이미지)", "scope", "PLATFORM", "audience", "PUBLIC",
                "startsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()))));
        String url = json(upload(sysAdminToken, scheduled, "preview.png", "image/png", PNG_BYTES)
                .andExpect(status().isCreated()).andReturn()).get("url").asString();

        MvcResult served = mockMvc.perform(get(url)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                // Not yet anonymously readable, so not shareable either — a
                // PLATFORM+PUBLIC notice still outside its window must not be
                // stored where an anonymous requester could pick it up before
                // it is published. private keeps the year precisely because it
                // cannot be reached by anyone but this caller.
                .andExpect(header().string("Cache-Control",
                        "private, max-age=31536000, immutable"))
                .andReturn();
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(PNG_BYTES);

        // The org tier manages its own org's notices and reads the platform's,
        // so it gets the preview too.
        mockMvc.perform(get(url).header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());

        // But an org-tier account granted no organisation has no management
        // screen that needs it: listAdminNotices refuses it outright, so the
        // preview would be the one surface where it saw a notice it cannot
        // list. The platform branch asks no organisation question, so this is
        // the account the two would otherwise disagree about.
        String unattachedToken = jwtService.createAccessToken(ensureUnattachedUser(
                "notice.preview.unattached@pusan.ac.kr", "무소속관리자", UserRole.ORG_ADMIN));
        adminList(unattachedToken).andExpect(status().isForbidden());
        mockMvc.perform(get(url).header("Authorization", "Bearer " + unattachedToken))
                .andExpect(status().isNotFound());

        // Nobody else does, and the refusal stays a 404.
        mockMvc.perform(get(url)).andExpect(status().isNotFound());
        mockMvc.perform(get(url).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
        // The widening is the image path only: the JSON read still applies the
        // window, because the body already reaches managers through their list.
        publicGet(sysAdminToken, scheduled).andExpect(status().isNotFound());

        // An org notice of a different organisation is not previewable either,
        // window or no window.
        String foreignUrl = json(upload(otherOrgAdminToken, otherOrgNotice, "foreign.png",
                "image/png", PNG_BYTES).andExpect(status().isCreated()).andReturn())
                .get("url").asString();
        mockMvc.perform(get(foreignUrl).header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isNotFound());
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

    private ResultActions deleteAdminImage(String token, UUID noticeId, UUID imageId)
            throws Exception {
        return mockMvc.perform(authorize(
                delete("/api/v1/admin/notices/" + noticeId + "/images/" + imageId), token));
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
        return ensureOrgUser(email, name, orgId, UserRole.ORG_ADMIN);
    }

    /**
     * An org-tier account holding {@code role} in {@code orgId}. Since V90 the
     * organisation is a {@code user_org_roles} row rather than a column on the
     * account, and {@code users.role} is the effective role the
     * {@code @PreAuthorize} gate reads — here the two agree, because the fixture
     * grants exactly one organisation.
     */
    private User ensureOrgUser(String email, String name, Long orgId, UserRole role) {
        User user = userRepository.findByEmail(email).orElseGet(() ->
                userRepository.save(new User(email, "{noop}unused", name)));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.save(user);
        SeedFixtures.grantOrgRole(jdbcTemplate, saved.getId(), orgId, role);
        return saved;
    }

    private User ensureRegularUser(String email, String name) {
        User user = userRepository.findByEmail(email).orElseGet(() ->
                userRepository.save(new User(email, "{noop}unused", name)));
        user.setRole(UserRole.USER);
        user.setStatus(UserStatus.ACTIVE);
        User saved = userRepository.save(user);
        // V90 left no column to clear: a regular account is one with no row.
        jdbcTemplate.update("delete from user_org_roles where user_id = ?", saved.getId());
        return saved;
    }

    /**
     * An account holding {@code role} and granted no organisation. Correct for
     * the sys tier, which is never org-scoped and whose roles the table's CHECK
     * would refuse anyway, and it is also how the org-tier account that holds no
     * row is built — the seam where the gate reads {@code users.role} and admits
     * the caller while the service refuses it.
     */
    private User ensureUnattachedUser(String email, String name, UserRole role) {
        User user = userRepository.findByEmail(email).orElseGet(() ->
                userRepository.save(new User(email, "{noop}unused", name)));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return userRepository.save(user);
    }

    private long orgRoleRows(long userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from user_org_roles where user_id = ?", Long.class, userId);
    }
}
