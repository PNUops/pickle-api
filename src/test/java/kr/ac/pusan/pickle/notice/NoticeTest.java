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
 * 공지사항 per contract v0.52.0: the one visibility axis and the 404 mask that
 * enforces it, the active window, and the image rules.
 *
 * <p><b>What V95 removed.</b> A notice used to carry a second axis naming the
 * organisation it belonged to, and most of this class used to be about who
 * counted as that organisation's reader. An organisation names who supplies a
 * node or a resource; it is not a mechanism for deciding who may use a feature,
 * so every notice is now platform-wide and every administrator writes for the
 * whole platform. The tests that pinned the organisation rules are gone, and
 * three new ones stand where they stood — one for each thing the removal made
 * newly reachable.</p>
 *
 * <p><b>The anonymous boundary is one predicate.</b> {@code audience = PUBLIC}
 * inside the publication window, and nothing else. That makes
 * {@code anonymousReaderSeesOnlyPublicNotices} the load-bearing test of this
 * class: it is the only thing standing between a USERS notice and the public
 * internet.</p>
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

    private UUID publicNotice;
    private UUID usersNotice;
    private UUID orgAdminNotice;

    @BeforeEach
    void setUp() throws Exception {
        org = orgRepository.findFirstByNameOrderByIdAsc(SeedFixtures.ORG_NAME).orElseThrow();
        otherOrg = orgRepository.findFirstByNameOrderByIdAsc("공지 타기관").orElseGet(() ->
                orgRepository.save(new Org("공지 타기관", null)));
        User otherOrgAdmin = ensureOrgUser("notice.other.admin@pusan.ac.kr", "공지타기관장",
                otherOrg.getId(), UserRole.ORG_ADMIN);
        User reader = ensureRegularUser("notice.reader@pusan.ac.kr", "공지독자");

        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        orgAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.ORGADMIN_EMAIL).orElseThrow());
        otherOrgAdminToken = jwtService.createAccessToken(otherOrgAdmin);
        userToken = jwtService.createAccessToken(reader);

        // Images cascade with their notice, so one delete clears both tables.
        jdbcTemplate.update("delete from notices");

        publicNotice = createdId(create(sysAdminToken, body(Map.of(
                "title", "전체 공개 공지", "audience", "PUBLIC"))));
        usersNotice = createdId(create(sysAdminToken, body(Map.of(
                "title", "로그인 전용 공지", "audience", "USERS"))));
        // An organisation administrator writes for the whole platform, so this
        // is the same kind of object the system administrator just created.
        orgAdminNotice = createdId(create(orgAdminToken, body(Map.of(
                "title", "기관 관리자 공지", "audience", "USERS"))));
    }

    @Test
    void anonymousReaderSeesOnlyPublicNotices() throws Exception {
        // The whole anonymous boundary: audience = PUBLIC inside the window.
        // Nothing else separates a signed-out caller from a signed-in one, so
        // this is the assertion that keeps USERS notices off the open internet.
        publicList(null)
                .andExpect(status().isOk())
                .andExpect(listHas(publicNotice))
                .andExpect(listOmits(usersNotice))
                .andExpect(listOmits(orgAdminNotice));

        // A notice an anonymous caller may not see is absent, not refused: a 403
        // would confirm that this identifier names a real notice.
        publicGet(null, publicNotice).andExpect(status().isOk());
        publicGet(null, usersNotice)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        publicGet(null, orgAdminNotice)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        // An identifier that names nothing answers exactly the same way, which
        // is what makes the two indistinguishable from outside.
        publicGet(null, SeedFixtures.UNKNOWN_ID)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void everySignedInReaderSeesEveryNoticeInItsWindow() throws Exception {
        // Being signed in is the entire widening. A regular account carrying no
        // organisation role and belonging to no workspace sees exactly what the
        // system administrator sees.
        assertThat(orgRoleRows(userRepository.findByEmail("notice.reader@pusan.ac.kr")
                .orElseThrow().getId()))
                .as("a regular account holds no organisation role").isZero();

        publicList(userToken)
                .andExpect(status().isOk())
                .andExpect(listHas(publicNotice))
                .andExpect(listHas(usersNotice))
                .andExpect(listHas(orgAdminNotice));
        publicGet(userToken, usersNotice).andExpect(status().isOk());
        publicGet(userToken, orgAdminNotice).andExpect(status().isOk());

        // Suspending the account takes the USERS notices away again. On a
        // permitAll path an unusable token is not a 401 — the filter simply
        // builds no principal — so the caller degrades to anonymous and sees
        // less, never more.
        User member = userRepository.findByEmail("notice.reader@pusan.ac.kr").orElseThrow();
        member.setStatus(UserStatus.DISABLED);
        userRepository.save(member);
        publicList(userToken)
                .andExpect(status().isOk())
                .andExpect(listHas(publicNotice))
                .andExpect(listOmits(usersNotice))
                .andExpect(listOmits(orgAdminNotice));
        publicGet(userToken, usersNotice).andExpect(status().isNotFound());
        member.setStatus(UserStatus.ACTIVE);
        userRepository.save(member);
    }

    @Test
    void anOrgAdminsNoticeReachesAReaderWithNoOrganisationRelationAtAll() throws Exception {
        // The rule V95 replaced: an organisation administrator's notice used to
        // reach only that organisation's derived members. This reader holds no
        // user_org_roles row and belongs to no workspace, so under the old rule
        // it was unreachable — and it is now the ordinary case.
        User stranger = ensureRegularUser("notice.stranger@pusan.ac.kr", "무관계독자");
        assertThat(orgRoleRows(stranger.getId())).isZero();
        assertThat(workspaceRows(stranger.getId()))
                .as("and no workspace to derive a membership from").isZero();
        String strangerToken = jwtService.createAccessToken(stranger);

        publicList(strangerToken)
                .andExpect(status().isOk())
                .andExpect(listHas(orgAdminNotice));
        publicGet(strangerToken, orgAdminNotice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("기관 관리자 공지"));

        // What still stops at the login is the audience, not any organisation
        // shape: the same notice is USERS, so anonymously it is absent.
        publicGet(null, orgAdminNotice).andExpect(status().isNotFound());
    }

    @Test
    void anOrgAdminPublishesToAnonymousVisitorsToo() throws Exception {
        // Previously unreachable from this role in both directions: an ORG
        // notice could not be PUBLIC (422) and a PLATFORM one was refused (403).
        UUID published = createdId(create(orgAdminToken, body(Map.of(
                "title", "기관 관리자 공개 공지", "audience", "PUBLIC"))));

        publicList(null)
                .andExpect(status().isOk())
                .andExpect(listHas(published));
        publicGet(null, published)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("기관 관리자 공개 공지"));

        // And its image is served to an anonymous caller under the shared-cache
        // directive, which re-checks that the cache rule is derived from the
        // visibility function and not from who wrote the notice.
        String url = json(upload(orgAdminToken, published, "open.png", "image/png", PNG_BYTES)
                .andExpect(status().isCreated()).andReturn()).get("url").asString();
        MvcResult served = mockMvc.perform(get(url))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        "public, max-age=31536000, s-maxage=3600"))
                .andReturn();
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(PNG_BYTES);
    }

    @Test
    void administratorsEditEachOthersNoticesWhicheverRoleWroteThem() throws Exception {
        // Nobody owns a notice, so the write scope is the gate and nothing more.
        patchNotice(orgAdminToken, publicNotice, Map.of("title", "시스템 공지(기관 관리자 수정)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("시스템 공지(기관 관리자 수정)"));
        patchNotice(sysAdminToken, orgAdminNotice, Map.of("title", "기관 관리자 공지(시스템 관리자 수정)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("기관 관리자 공지(시스템 관리자 수정)"));

        // An administrator of a different organisation is not a different case:
        // there is no organisation on a notice for it to be different from.
        patchNotice(otherOrgAdminToken, orgAdminNotice, Map.of("title", "타기관 관리자 수정"))
                .andExpect(status().isOk());
        upload(otherOrgAdminToken, publicNotice, "a.png", "image/png", PNG_BYTES)
                .andExpect(status().isCreated());
        deleteNotice(otherOrgAdminToken, publicNotice).andExpect(status().isNoContent());

        // An org-tier account granted no organisation at all reaches both the
        // management list and the writes. Refusing it used to be the one place
        // an unattached account was told no, and that refusal was exactly an
        // organisation gating a feature.
        String unattachedToken = jwtService.createAccessToken(ensureUnattachedUser(
                "notice.unattached@pusan.ac.kr", "무소속관리자", UserRole.ORG_ADMIN));
        adminList(unattachedToken)
                .andExpect(status().isOk())
                .andExpect(listHas(orgAdminNotice));
        create(unattachedToken, body(Map.of("title", "무소속 등록", "audience", "USERS")))
                .andExpect(status().isCreated());

        // A notice that does not exist is still 404 — a fact now, not a mask.
        patchNotice(sysAdminToken, SeedFixtures.UNKNOWN_ID, Map.of("title", "없는 공지"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void theActiveWindowFiltersThePublicListButNotTheAdminOne() throws Exception {
        Instant now = Instant.now();
        UUID scheduled = createdId(create(sysAdminToken, body(Map.of(
                "title", "예정 공지", "audience", "PUBLIC",
                "startsAt", now.plus(1, ChronoUnit.DAYS).toString()))));
        UUID expired = createdId(create(sysAdminToken, body(Map.of(
                "title", "만료 공지", "audience", "PUBLIC",
                "startsAt", now.minus(2, ChronoUnit.DAYS).toString(),
                "endsAt", now.minus(1, ChronoUnit.DAYS).toString()))));

        publicList(null)
                .andExpect(listHas(publicNotice))
                .andExpect(listOmits(scheduled))
                .andExpect(listOmits(expired));
        publicGet(null, scheduled).andExpect(status().isNotFound());
        publicGet(null, expired).andExpect(status().isNotFound());
        // The window binds a signed-in reader too: it is the half of the
        // visibility rule that has nothing to do with who is asking.
        publicGet(userToken, scheduled).andExpect(status().isNotFound());
        publicGet(userToken, expired).andExpect(status().isNotFound());

        // The admin list is the whole surface, each row saying where it stands.
        adminList(sysAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath(rowWhere(scheduled, "@.active==false")).exists())
                .andExpect(jsonPath(rowWhere(expired, "@.active==false")).exists())
                .andExpect(jsonPath(rowWhere(publicNotice, "@.active==true")).exists())
                // list rows carry the body, which is why there is no admin detail read
                .andExpect(jsonPath(rowWhere(publicNotice, "@.body")).exists());

        // A window that closes before it opens is refused here rather than left
        // to notices_window_check, which would surface as a 500.
        create(sysAdminToken, body(Map.of(
                "title", "거꾸로", "audience", "PUBLIC",
                "startsAt", now.toString(),
                "endsAt", now.minus(1, ChronoUnit.HOURS).toString())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='endsAt')]").exists());
    }

    @Test
    void anOmittedEditFieldKeepsItsValueAndAnExplicitNullClearsTheExpiry() throws Exception {
        Instant endsAt = Instant.now().plus(7, ChronoUnit.DAYS);
        UUID expiring = createdId(create(sysAdminToken, body(Map.of(
                "title", "기간 있는 공지", "audience", "PUBLIC",
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

        // The list is unscoped, so a viewer of one organisation reads the
        // notices another organisation's administrator wrote, like everyone else.
        adminList(orgViewerToken)
                .andExpect(status().isOk())
                .andExpect(listHas(publicNotice))
                .andExpect(listHas(orgAdminNotice));

        // Every write is refused by the method's own gate, which fully replaces
        // the widened class-level one — ACCESS_DENIED rather than any other 403,
        // so a refusal from somewhere else in the chain cannot pass for this.
        create(orgViewerToken, body(Map.of("title", "열람자 등록 시도", "audience", "USERS")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        patchNotice(orgViewerToken, orgAdminNotice, Map.of("title", "열람자 수정 시도"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        deleteNotice(orgViewerToken, orgAdminNotice)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        upload(orgViewerToken, orgAdminNotice, "a.png", "image/png", PNG_BYTES)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // The system viewer reads the same list and writes none of it either.
        User sysViewer = ensureUnattachedUser("notice.sys.viewer@pusan.ac.kr", "공지전체열람자",
                UserRole.SYS_VIEWER);
        String sysViewerToken = jwtService.createAccessToken(sysViewer);

        adminList(sysViewerToken)
                .andExpect(status().isOk())
                .andExpect(listHas(publicNotice))
                .andExpect(listHas(orgAdminNotice));

        create(sysViewerToken, body(Map.of("title", "전체열람자 등록 시도", "audience", "PUBLIC")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        patchNotice(sysViewerToken, publicNotice, Map.of("title", "전체열람자 수정 시도"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        deleteNotice(sysViewerToken, orgAdminNotice)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        // A regular account never reaches the surface at all.
        adminList(userToken).andExpect(status().isForbidden());

        // Nothing the refused writes attempted actually landed.
        adminList(sysAdminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath(rowWhere(orgAdminNotice, "@.title=='기관 관리자 공지'")).exists())
                .andExpect(listHas(publicNotice));
    }

    @Test
    void imageUploadsAreJudgedByTheirBytesAndCapped() throws Exception {
        // A text payload wearing image/png is refused: the declared type is a
        // claim, and the leading bytes are what decide.
        upload(sysAdminToken, publicNotice, "fake.png", "image/png",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOTICE_IMAGE_TYPE_UNSUPPORTED"));

        byte[] oversized = new byte[NoticeImageTypes.MAX_BYTES + 1];
        System.arraycopy(PNG_BYTES, 0, oversized, 0, PNG_BYTES.length);
        upload(sysAdminToken, publicNotice, "big.png", "image/png", oversized)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("NOTICE_IMAGE_TOO_LARGE"));

        // The stored type is the real one, not the one the upload declared.
        UUID firstImageId = UUID.fromString(json(
                upload(sysAdminToken, publicNotice, "photo.png", "image/png", JPEG_BYTES)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                        .andExpect(jsonPath("$.byteSize").value(JPEG_BYTES.length))
                        .andReturn()).get("id").asString());

        // Four more fill the notice; the one after that is refused.
        for (int i = 2; i <= NoticeImageTypes.MAX_PER_NOTICE; i++) {
            upload(sysAdminToken, publicNotice, "img" + i + ".png", "image/png", PNG_BYTES)
                    .andExpect(status().isCreated());
        }
        upload(sysAdminToken, publicNotice, "one-too-many.png", "image/png", PNG_BYTES)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTICE_IMAGE_LIMIT_EXCEEDED"));

        // Deleting one frees a slot, and the row really goes: the cap is
        // counted from the store rather than from anything the notice caches.
        long noticeId = SeedFixtures.internalId(jdbcTemplate, "notices", publicNotice);
        assertThat(imageRows(noticeId)).isEqualTo(NoticeImageTypes.MAX_PER_NOTICE);
        deleteAdminImage(sysAdminToken, publicNotice, firstImageId)
                .andExpect(status().isNoContent());
        assertThat(imageRows(noticeId)).isEqualTo(NoticeImageTypes.MAX_PER_NOTICE - 1);
        // The same id twice is 404, not a second silent success.
        deleteAdminImage(sysAdminToken, publicNotice, firstImageId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        upload(sysAdminToken, publicNotice, "refill.png", "image/png", PNG_BYTES)
                .andExpect(status().isCreated());
    }

    @Test
    void servingAnImageReturnsTheStoredBytesAndInheritsTheNoticesVisibility() throws Exception {
        JsonNode uploaded = json(upload(sysAdminToken, publicNotice, "hero.png", "image/png",
                PNG_BYTES).andExpect(status().isCreated()).andReturn());
        UUID imageId = UUID.fromString(uploaded.get("id").asString());
        String url = uploaded.get("url").asString();
        // The finished string, not just the ids behind it: this is a
        // concatenation site, and nothing downstream would object to a wrong one.
        assertThat(url).isEqualTo("/api/v1/notices/" + publicNotice + "/images/" + imageId);

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

        // The notice's own visibility governs its images: this one is USERS, so
        // it is absent anonymously and private when it is served.
        JsonNode restricted = json(upload(sysAdminToken, usersNotice, "members.png", "image/png",
                PNG_BYTES).andExpect(status().isCreated()).andReturn());
        String restrictedUrl = restricted.get("url").asString();
        mockMvc.perform(get(restrictedUrl)).andExpect(status().isNotFound());
        // Whose answer this is depends on who asked, and the response carries no
        // Vary that would tell two callers apart — so it must never be storable
        // in a cache shared between them. private still caches in the
        // requester's own browser, which is all this ever needed, and keeps the
        // year: re-serving bytes to the one caller who already received them is
        // indistinguishable from their having saved the file.
        mockMvc.perform(get(restrictedUrl).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        "private, max-age=31536000, immutable"));

        // An image reached through a notice that does not own it is absent too,
        // even when that notice is one the caller may read.
        mockMvc.perform(get("/api/v1/notices/" + publicNotice + "/images/"
                        + restricted.get("id").asString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingANoticeTakesItsImagesAndLeavesAnAuditTrail() throws Exception {
        upload(sysAdminToken, publicNotice, "gone.png", "image/png", PNG_BYTES)
                .andExpect(status().isCreated());
        long noticeId = SeedFixtures.internalId(jdbcTemplate, "notices", publicNotice);
        assertThat(imageRows(noticeId)).isEqualTo(1);

        deleteNotice(sysAdminToken, publicNotice).andExpect(status().isNoContent());
        assertThat(imageRows(noticeId)).isZero();
        publicGet(null, publicNotice).andExpect(status().isNotFound());

        assertThat(auditRows("notice.create", orgAdminNotice)).isEqualTo(1);
        assertThat(auditRows("notice.image_add", publicNotice)).isEqualTo(1);
        assertThat(auditRows("notice.delete", publicNotice)).isEqualTo(1);
    }

    @Test
    void managementCanSeeTheImagesOfANoticeThatIsNotPublishedYet() throws Exception {
        // The management screen shows a scheduled notice before it goes live,
        // and its list row carries the image URLs. Sending those through the
        // public window check alone would 404 every one of them.
        UUID scheduled = createdId(create(sysAdminToken, body(Map.of(
                "title", "예정 공지(이미지)", "audience", "PUBLIC",
                "startsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()))));
        String url = json(upload(sysAdminToken, scheduled, "preview.png", "image/png", PNG_BYTES)
                .andExpect(status().isCreated()).andReturn()).get("url").asString();

        MvcResult served = mockMvc.perform(get(url)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                // Not yet anonymously readable, so not shareable either — a
                // PUBLIC notice still outside its window must not be stored
                // where an anonymous requester could pick it up before it is
                // published. This is the pair that pins the cache directive to
                // the visibility function rather than to the audience field:
                // asking audience == PUBLIC alone would mark this one shared.
                // private keeps the year precisely because it cannot be reached
                // by anyone but this caller.
                .andExpect(header().string("Cache-Control",
                        "private, max-age=31536000, immutable"))
                .andReturn();
        assertThat(served.getResponse().getContentAsByteArray()).isEqualTo(PNG_BYTES);

        // Every role on the management surface gets the preview, an org-tier
        // account granted no organisation included — it reads the same list.
        mockMvc.perform(get(url).header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isOk());
        String unattachedToken = jwtService.createAccessToken(ensureUnattachedUser(
                "notice.preview.unattached@pusan.ac.kr", "무소속관리자", UserRole.ORG_ADMIN));
        adminList(unattachedToken).andExpect(status().isOk());
        mockMvc.perform(get(url).header("Authorization", "Bearer " + unattachedToken))
                .andExpect(status().isOk());

        // Nobody below that surface does, and the refusal stays a 404.
        mockMvc.perform(get(url)).andExpect(status().isNotFound());
        mockMvc.perform(get(url).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isNotFound());
        // The widening is the image path only: the JSON read still applies the
        // window, because the body already reaches managers through their list.
        publicGet(sysAdminToken, scheduled).andExpect(status().isNotFound());
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

    /**
     * An org-tier account holding {@code role} in {@code orgId}. Since V90 the
     * organisation is a {@code user_org_roles} row rather than a column on the
     * account, and {@code users.role} is the effective role the
     * {@code @PreAuthorize} gate reads — here the two agree, because the fixture
     * grants exactly one organisation. Notices no longer ask the question, but
     * the grant is still what makes these accounts realistic.
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
     * row is built — the account the notice surface used to refuse and now
     * admits.
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

    private long workspaceRows(long userId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from workspace_members where user_id = ?", Long.class, userId);
    }
}
