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
import java.util.LinkedHashMap;
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
 * 공지사항 per contract v0.52.0: the single visibility flag and the 404 mask
 * that enforces it, the active window, and the image rules.
 *
 * <p><b>What V95 removed.</b> A notice used to carry two axes. One named the
 * organisation it belonged to, and most of this class used to be about who
 * counted as that organisation's reader; an organisation names who supplies a
 * node or a resource, not who may use a feature, so every notice is now
 * platform-wide. The other was {@code audience}, which duplicated a decision
 * the author was already making, so {@code popup} absorbed it.</p>
 *
 * <p><b>The anonymous boundary is one predicate.</b> {@code popup = true}
 * inside the publication window, and nothing else. That makes
 * {@code anonymousReaderSeesOnlyPopupNotices} the load-bearing test of this
 * class: it is the only thing standing between a board notice and the public
 * internet.</p>
 *
 * <p><b>The coupling that follows.</b> A notice worth interrupting a reader for
 * is now necessarily a notice a visitor who cannot sign in may read. There is
 * no modal-for-signed-in-readers-only, and
 * {@code aPopupIsAlwaysAnonymouslyReadable} pins that as a property rather than
 * leaving it as an accident of two flags happening to agree.</p>
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

    private UUID popupNotice;
    private UUID boardNotice;
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

        popupNotice = createdId(create(sysAdminToken, body(Map.of(
                "title", "팝업 공지", "popup", true))));
        boardNotice = createdId(create(sysAdminToken, body(Map.of(
                "title", "게시판 공지"))));
        // An organisation administrator writes for the whole platform, so this
        // is the same kind of object the system administrator just created.
        orgAdminNotice = createdId(create(orgAdminToken, body(Map.of(
                "title", "기관 관리자 공지"))));
    }

    @Test
    void anonymousReaderSeesOnlyPopupNotices() throws Exception {
        // The whole anonymous boundary: popup = true inside the window. Nothing
        // else separates a signed-out caller from a signed-in one, so this is
        // the assertion that keeps board notices off the open internet.
        publicList(null)
                .andExpect(status().isOk())
                .andExpect(listHas(popupNotice))
                .andExpect(listOmits(boardNotice))
                .andExpect(listOmits(orgAdminNotice));

        // A notice an anonymous caller may not see is absent, not refused: a 403
        // would confirm that this identifier names a real notice.
        publicGet(null, popupNotice).andExpect(status().isOk());
        publicGet(null, boardNotice)
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
                .andExpect(listHas(popupNotice))
                .andExpect(listHas(boardNotice))
                .andExpect(listHas(orgAdminNotice));
        publicGet(userToken, boardNotice).andExpect(status().isOk());
        publicGet(userToken, orgAdminNotice).andExpect(status().isOk());

        // Suspending the account takes the board notices away again. On a
        // permitAll path an unusable token is not a 401 — the filter simply
        // builds no principal — so the caller degrades to anonymous and sees
        // less, never more.
        User member = userRepository.findByEmail("notice.reader@pusan.ac.kr").orElseThrow();
        member.setStatus(UserStatus.DISABLED);
        userRepository.save(member);
        publicList(userToken)
                .andExpect(status().isOk())
                .andExpect(listHas(popupNotice))
                .andExpect(listOmits(boardNotice))
                .andExpect(listOmits(orgAdminNotice));
        publicGet(userToken, boardNotice).andExpect(status().isNotFound());
        member.setStatus(UserStatus.ACTIVE);
        userRepository.save(member);
    }

    @Test
    void theAnonymousQueryAndTheVisibilityFunctionAgreeOnEveryCase() throws Exception {
        // The anonymous rule is written twice: once as SQL in
        // findVisibleToAnonymous, which serves the public list, and once as
        // Java in visibleTo(..., null, ...), which serves the detail read AND
        // is what the image cache directive is derived from. Both say "popup,
        // inside the window", and nothing but this test ever compares them. If
        // they drift, an image becomes markable for shared caches that the list
        // will not show — or the reverse — and every other test still passes,
        // because each one exercises a single surface.
        //
        // So: all four combinations of the two halves, checked on all three
        // surfaces at once.
        Instant now = Instant.now();
        Map<String, UUID> cases = new LinkedHashMap<>();
        cases.put("팝업·게시중", createdId(create(sysAdminToken, body(Map.of(
                "title", "팝업 게시중", "popup", true)))));
        cases.put("팝업·예정", createdId(create(sysAdminToken, body(Map.of(
                "title", "팝업 예정", "popup", true,
                "startsAt", now.plus(1, ChronoUnit.DAYS).toString())))));
        cases.put("팝업·만료", createdId(create(sysAdminToken, body(Map.of(
                "title", "팝업 만료", "popup", true,
                "startsAt", now.minus(2, ChronoUnit.DAYS).toString(),
                "endsAt", now.minus(1, ChronoUnit.DAYS).toString())))));
        cases.put("게시판·게시중", createdId(create(sysAdminToken, body(Map.of(
                "title", "게시판 게시중")))));
        cases.put("게시판·예정", createdId(create(sysAdminToken, body(Map.of(
                "title", "게시판 예정",
                "startsAt", now.plus(1, ChronoUnit.DAYS).toString())))));
        cases.put("게시판·만료", createdId(create(sysAdminToken, body(Map.of(
                "title", "게시판 만료",
                "startsAt", now.minus(2, ChronoUnit.DAYS).toString(),
                "endsAt", now.minus(1, ChronoUnit.DAYS).toString())))));

        for (Map.Entry<String, UUID> entry : cases.entrySet()) {
            // Exactly one case is anonymously readable. Deriving the expectation
            // from the case name rather than restating the rule keeps this test
            // from re-implementing what it is checking.
            boolean anonymouslyReadable = entry.getKey().equals("팝업·게시중");
            UUID id = entry.getValue();

            // 1. the SQL, through the public list
            publicList(null).andExpect(status().isOk())
                    .andExpect(anonymouslyReadable ? listHas(id) : listOmits(id));
            // 2. the Java, through the detail read
            publicGet(null, id).andExpect(anonymouslyReadable
                    ? status().isOk() : status().isNotFound());
            // 3. the Java again, through the cache directive an authenticated
            //    fetch carries — the answer must be the same one, which is the
            //    whole reason image() calls the function instead of restating it
            String url = json(upload(sysAdminToken, id, "c.png", "image/png", PNG_BYTES)
                    .andExpect(status().isCreated()).andReturn()).get("url").asString();
            mockMvc.perform(get(url).header("Authorization", "Bearer " + sysAdminToken))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", anonymouslyReadable
                            ? "public, max-age=31536000, s-maxage=3600"
                            : "private, max-age=31536000, immutable"));
        }
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

        // What still stops at the login is the popup flag, not any organisation
        // shape: this notice is not a popup, so anonymously it is absent.
        publicGet(null, orgAdminNotice).andExpect(status().isNotFound());
    }

    @Test
    void anOrgAdminPublishesToAnonymousVisitorsToo() throws Exception {
        // Previously unreachable from this role in both directions: an ORG
        // notice could not be publicly readable (422) and a platform one was
        // refused (403). Now one flag does it.
        UUID published = createdId(create(orgAdminToken, body(Map.of(
                "title", "기관 관리자 팝업 공지", "popup", true))));

        publicList(null)
                .andExpect(status().isOk())
                .andExpect(listHas(published));
        publicGet(null, published)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("기관 관리자 팝업 공지"));

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
    void aPopupIsAlwaysAnonymouslyReadableAndTheTwoCannotBeSeparated() throws Exception {
        // popup carries two meanings at once since V95: it raises the modal AND
        // it opens the notice to visitors who cannot sign in. The combination
        // this removes — interrupt signed-in readers only — is not merely
        // unreachable through the console, it does not exist in the model, and
        // that is the point worth pinning rather than leaving to two flags
        // happening to agree.
        //
        // Asserted as a pair on the same notice, flipped in place, because
        // either half alone reads as a coincidence.
        UUID notice = createdId(create(sysAdminToken, body(Map.of(
                "title", "결합 확인", "popup", false))));

        publicList(null).andExpect(status().isOk()).andExpect(listOmits(notice));
        publicGet(null, notice).andExpect(status().isNotFound());
        publicGet(userToken, notice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.popup").value(false));

        // Turning the modal on is what opens it up. There is no third state.
        patchNotice(sysAdminToken, notice, Map.of("popup", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.popup").value(true));
        publicList(null).andExpect(status().isOk()).andExpect(listHas(notice));
        publicGet(null, notice)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.popup").value(true));

        // And turning it off closes it again, so the edit is not one-way.
        patchNotice(sysAdminToken, notice, Map.of("popup", false))
                .andExpect(status().isOk());
        publicGet(null, notice).andExpect(status().isNotFound());

        // The response body carries no separate visibility field for a client
        // to disagree with: popup is the only one, and pinned is about order.
        publicGet(userToken, notice)
                .andExpect(jsonPath("$.audience").doesNotExist())
                .andExpect(jsonPath("$.pinned").exists())
                .andExpect(jsonPath("$.popup").exists());
    }

    @Test
    void administratorsEditEachOthersNoticesWhicheverRoleWroteThem() throws Exception {
        // Nobody owns a notice, so the write scope is the gate and nothing more.
        patchNotice(orgAdminToken, popupNotice, Map.of("title", "시스템 공지(기관 관리자 수정)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("시스템 공지(기관 관리자 수정)"));
        patchNotice(sysAdminToken, orgAdminNotice, Map.of("title", "기관 관리자 공지(시스템 관리자 수정)"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("기관 관리자 공지(시스템 관리자 수정)"));

        // An administrator of a different organisation is not a different case:
        // there is no organisation on a notice for it to be different from.
        patchNotice(otherOrgAdminToken, orgAdminNotice, Map.of("title", "타기관 관리자 수정"))
                .andExpect(status().isOk());
        upload(otherOrgAdminToken, popupNotice, "a.png", "image/png", PNG_BYTES)
                .andExpect(status().isCreated());
        deleteNotice(otherOrgAdminToken, popupNotice).andExpect(status().isNoContent());

        // An org-tier account granted no organisation at all reaches both the
        // management list and the writes. Refusing it used to be the one place
        // an unattached account was told no, and that refusal was exactly an
        // organisation gating a feature.
        String unattachedToken = jwtService.createAccessToken(ensureUnattachedUser(
                "notice.unattached@pusan.ac.kr", "무소속관리자", UserRole.ORG_ADMIN));
        adminList(unattachedToken)
                .andExpect(status().isOk())
                .andExpect(listHas(orgAdminNotice));
        create(unattachedToken, body(Map.of("title", "무소속 등록")))
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
                "title", "예정 공지", "popup", true,
                "startsAt", now.plus(1, ChronoUnit.DAYS).toString()))));
        UUID expired = createdId(create(sysAdminToken, body(Map.of(
                "title", "만료 공지", "popup", true,
                "startsAt", now.minus(2, ChronoUnit.DAYS).toString(),
                "endsAt", now.minus(1, ChronoUnit.DAYS).toString()))));

        publicList(null)
                .andExpect(listHas(popupNotice))
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
                .andExpect(jsonPath(rowWhere(popupNotice, "@.active==true")).exists())
                // list rows carry the body, which is why there is no admin detail read
                .andExpect(jsonPath(rowWhere(popupNotice, "@.body")).exists());

        // A window that closes before it opens is refused here rather than left
        // to notices_window_check, which would surface as a 500.
        create(sysAdminToken, body(Map.of(
                "title", "거꾸로", "popup", true,
                "startsAt", now.toString(),
                "endsAt", now.minus(1, ChronoUnit.HOURS).toString())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field=='endsAt')]").exists());
    }

    @Test
    void anOmittedEditFieldKeepsItsValueAndAnExplicitNullClearsTheExpiry() throws Exception {
        Instant endsAt = Instant.now().plus(7, ChronoUnit.DAYS);
        UUID expiring = createdId(create(sysAdminToken, body(Map.of(
                "title", "기간 있는 공지", "popup", true,
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
                .andExpect(listHas(popupNotice))
                .andExpect(listHas(orgAdminNotice));

        // Every write is refused by the method's own gate, which fully replaces
        // the widened class-level one — ACCESS_DENIED rather than any other 403,
        // so a refusal from somewhere else in the chain cannot pass for this.
        create(orgViewerToken, body(Map.of("title", "열람자 등록 시도")))
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
                .andExpect(listHas(popupNotice))
                .andExpect(listHas(orgAdminNotice));

        create(sysViewerToken, body(Map.of("title", "전체열람자 등록 시도", "popup", true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        patchNotice(sysViewerToken, popupNotice, Map.of("title", "전체열람자 수정 시도"))
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
                .andExpect(listHas(popupNotice));
    }

    @Test
    void imageUploadsAreJudgedByTheirBytesAndCapped() throws Exception {
        // A text payload wearing image/png is refused: the declared type is a
        // claim, and the leading bytes are what decide.
        upload(sysAdminToken, popupNotice, "fake.png", "image/png",
                "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NOTICE_IMAGE_TYPE_UNSUPPORTED"));

        byte[] oversized = new byte[NoticeImageTypes.MAX_BYTES + 1];
        System.arraycopy(PNG_BYTES, 0, oversized, 0, PNG_BYTES.length);
        upload(sysAdminToken, popupNotice, "big.png", "image/png", oversized)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("NOTICE_IMAGE_TOO_LARGE"));

        // The stored type is the real one, not the one the upload declared.
        UUID firstImageId = UUID.fromString(json(
                upload(sysAdminToken, popupNotice, "photo.png", "image/png", JPEG_BYTES)
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.contentType").value("image/jpeg"))
                        .andExpect(jsonPath("$.byteSize").value(JPEG_BYTES.length))
                        .andReturn()).get("id").asString());

        // Four more fill the notice; the one after that is refused.
        for (int i = 2; i <= NoticeImageTypes.MAX_PER_NOTICE; i++) {
            upload(sysAdminToken, popupNotice, "img" + i + ".png", "image/png", PNG_BYTES)
                    .andExpect(status().isCreated());
        }
        upload(sysAdminToken, popupNotice, "one-too-many.png", "image/png", PNG_BYTES)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTICE_IMAGE_LIMIT_EXCEEDED"));

        // Deleting one frees a slot, and the row really goes: the cap is
        // counted from the store rather than from anything the notice caches.
        long noticeId = SeedFixtures.internalId(jdbcTemplate, "notices", popupNotice);
        assertThat(imageRows(noticeId)).isEqualTo(NoticeImageTypes.MAX_PER_NOTICE);
        deleteAdminImage(sysAdminToken, popupNotice, firstImageId)
                .andExpect(status().isNoContent());
        assertThat(imageRows(noticeId)).isEqualTo(NoticeImageTypes.MAX_PER_NOTICE - 1);
        // The same id twice is 404, not a second silent success.
        deleteAdminImage(sysAdminToken, popupNotice, firstImageId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        upload(sysAdminToken, popupNotice, "refill.png", "image/png", PNG_BYTES)
                .andExpect(status().isCreated());
    }

    @Test
    void servingAnImageReturnsTheStoredBytesAndInheritsTheNoticesVisibility() throws Exception {
        JsonNode uploaded = json(upload(sysAdminToken, popupNotice, "hero.png", "image/png",
                PNG_BYTES).andExpect(status().isCreated()).andReturn());
        UUID imageId = UUID.fromString(uploaded.get("id").asString());
        String url = uploaded.get("url").asString();
        // The finished string, not just the ids behind it: this is a
        // concatenation site, and nothing downstream would object to a wrong one.
        assertThat(url).isEqualTo("/api/v1/notices/" + popupNotice + "/images/" + imageId);

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

        // The notice's own visibility governs its images: this one is not a
        // popup, so it is absent anonymously and private when it is served.
        JsonNode restricted = json(upload(sysAdminToken, boardNotice, "members.png", "image/png",
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
        mockMvc.perform(get("/api/v1/notices/" + popupNotice + "/images/"
                        + restricted.get("id").asString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingANoticeTakesItsImagesAndLeavesAnAuditTrail() throws Exception {
        upload(sysAdminToken, popupNotice, "gone.png", "image/png", PNG_BYTES)
                .andExpect(status().isCreated());
        long noticeId = SeedFixtures.internalId(jdbcTemplate, "notices", popupNotice);
        assertThat(imageRows(noticeId)).isEqualTo(1);

        deleteNotice(sysAdminToken, popupNotice).andExpect(status().isNoContent());
        assertThat(imageRows(noticeId)).isZero();
        publicGet(null, popupNotice).andExpect(status().isNotFound());

        assertThat(auditRows("notice.create", orgAdminNotice)).isEqualTo(1);
        assertThat(auditRows("notice.image_add", popupNotice)).isEqualTo(1);
        assertThat(auditRows("notice.delete", popupNotice)).isEqualTo(1);
    }

    @Test
    void managementCanSeeTheImagesOfANoticeThatIsNotPublishedYet() throws Exception {
        // The management screen shows a scheduled notice before it goes live,
        // and its list row carries the image URLs. Sending those through the
        // public window check alone would 404 every one of them.
        UUID scheduled = createdId(create(sysAdminToken, body(Map.of(
                "title", "예정 공지(이미지)", "popup", true,
                "startsAt", Instant.now().plus(1, ChronoUnit.DAYS).toString()))));
        String url = json(upload(sysAdminToken, scheduled, "preview.png", "image/png", PNG_BYTES)
                .andExpect(status().isCreated()).andReturn()).get("url").asString();

        MvcResult served = mockMvc.perform(get(url)
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                // Not yet anonymously readable, so not shareable either — a
                // popup notice still outside its window must not be stored
                // where an anonymous requester could pick it up before it is
                // published. This is the pair that pins the cache directive to
                // the visibility function rather than to the flag alone: asking
                // notice.isPopup() by itself would mark this one shared.
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
