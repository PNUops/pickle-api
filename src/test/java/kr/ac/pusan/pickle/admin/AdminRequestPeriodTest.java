package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import kr.ac.pusan.pickle.config.ClockConfig;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Usage-period catalogue (V104), the request form's third axis: the sys-tier
 * list, the SYS_ADMIN-only create/edit with change-only auditing, and the
 * asymmetry that is the reason this catalogue exists — the admin list carries
 * periods whose date has passed, the request form does not.
 *
 * <p>Every date here is derived in KST, the timezone the offer decision is made
 * in ({@link ClockConfig#todayKst}); the clock bean runs on UTC, so a test that
 * reached for the system default would drift by a day around UTC midnight.</p>
 */
@SpringBootTest(properties = "jobrunr.background-job-server.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminRequestPeriodTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String sysAdminToken;
    private String sysManagerToken;
    private String sysViewerToken;
    private String orgAdminToken;

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        sysManagerToken = jwtService.createAccessToken(
                ensureUser("arp.sysmanager@pusan.ac.kr", UserRole.SYS_MANAGER));
        sysViewerToken = jwtService.createAccessToken(
                ensureUser("arp.sysviewer@pusan.ac.kr", UserRole.SYS_VIEWER));
        orgAdminToken = jwtService.createAccessToken(
                ensureUser("arp.orgadmin@pusan.ac.kr", UserRole.ORG_ADMIN));
    }

    /**
     * The asymmetry the catalogue exists for. A term's date is absolute, so
     * last term's row stops being a choice on a calendar rather than on a
     * decision; the request form must not offer it, and the operator must still
     * see it, because a list that silently drops expired rows is a list in
     * which "this term is missing" looks the same as "everything is fine".
     */
    @Test
    void adminListCarriesExpiredPeriodsTheRequestFormHides() throws Exception {
        UUID lastTerm = insertPeriod("지난 학기", today().minusDays(1), "ACTIVE", 40);
        UUID endingToday = insertPeriod("오늘 끝나는 기간", today(), "ACTIVE", 41);

        mockMvc.perform(get("/api/v1/admin/request-periods")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(lastTerm) + ".expired").value(true))
                .andExpect(jsonPath(byId(lastTerm) + ".endDate")
                        .value(today().minusDays(1).toString()))
                // 종료일이 오늘인 항목은 아직 끝나지 않았다 — 계약상 종료일은 그날
                // 자정까지 유효하므로 경계는 '오늘 이전'이지 '오늘 이하'가 아니다
                .andExpect(jsonPath(byId(endingToday) + ".expired").value(false));

        mockMvc.perform(get("/api/v1/request-periods")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(lastTerm)).isEmpty())
                .andExpect(jsonPath(byId(endingToday)).isNotEmpty());
    }

    @Test
    void createIsSysAdminOnlyAndRejectsADuplicateName() throws Exception {
        String name = uniqueName();
        String body = """
                {"name": "%s", "displayName": "2026학년도 1학기", "endDate": "%s",
                 "displayOrder": 12}
                """.formatted(name, today().plusMonths(3));

        mockMvc.perform(post("/api/v1/admin/request-periods")
                        .header("Authorization", "Bearer " + sysManagerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/request-periods")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.displayName").value("2026학년도 1학기"))
                .andExpect(jsonPath("$.endDate").value(today().plusMonths(3).toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.displayOrder").value(12))
                .andExpect(jsonPath("$.expired").value(false))
                .andExpect(jsonPath("$.id").isNotEmpty());
        assertThat(auditCount("request-period.create", publicIdOf(name))).isEqualTo(1);

        // the name is the stable reference an audit entry points at, so a
        // duplicate is a field error rather than a 500 at commit
        mockMvc.perform(post("/api/v1/admin/request-periods")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("이미 사용 중인 기간 이름입니다."));

        // 이름은 대소문자를 무시하고 유일하다. API 는 소문자만 받으므로 섞인 이름은
        // 직접 써 넣어야 하고, 그때 서비스의 사전 검사(정확히 일치)는 비켜 간다.
        // 그 경로에서도 유일 인덱스가 같은 422 를 돌려주어야 한다.
        String mixedCase = uniqueName();
        jdbcTemplate.update("""
                insert into request_period_presets (name, display_name, end_date, display_order)
                values (?, '대소문자 확인', null, 44)
                """, mixedCase.toUpperCase());
        mockMvc.perform(post("/api/v1/admin/request-periods")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "displayName": "소문자로 다시"}
                                """.formatted(mixedCase)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("name"))
                .andExpect(jsonPath("$.errors[0].message").value("이미 사용 중인 기간 이름입니다."));

        // bean validation still applies: the name is a slug, not free text
        mockMvc.perform(post("/api/v1/admin/request-periods")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "Spring Term", "displayName": "잘못된 이름"}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    /**
     * 무기한은 종료일을 비운 항목으로만 존재한다. 신청 화면의 체크박스가 아니라 운영자가
     * 발행한 행이라, 만료되지 않는 리소스를 누가 신청할 수 있는지가 운영자의 결정으로
     * 남는다.
     */
    @Test
    void aPeriodCreatedWithNoEndDateIsTheIndefiniteOne() throws Exception {
        String name = uniqueName();
        mockMvc.perform(post("/api/v1/admin/request-periods")
                        .header("Authorization", "Bearer " + sysAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "displayName": "무기한 (연구실 서버)", "displayOrder": 20}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.endDate").value((Object) null))
                // 종료일이 없으면 지날 날짜도 없다
                .andExpect(jsonPath("$.expired").value(false));

        UUID id = publicIdOf(name);
        mockMvc.perform(get("/api/v1/request-periods")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(id) + ".displayName").value("무기한 (연구실 서버)"))
                .andExpect(jsonPath(byId(id) + ".endDate").value((Object) null));
    }

    @Test
    void partialEditMovesTheDateAndClearsItOnlyWhenAsked() throws Exception {
        UUID id = insertPeriod("이번 방학", today().plusMonths(2), "ACTIVE", 30);

        // 지우기와 지정은 서로 반대라 함께 올 수 없다 — 어느 쪽을 쓸지 정할 수 없다
        patchPeriod(id, sysAdminToken, """
                {"endDate": "%s", "clearEndDate": true}
                """.formatted(today().plusMonths(3)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("endDate"));

        // 빈 본문은 편집이 아니라 아무 요청도 아니다
        patchPeriod(id, sysAdminToken, "{}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // 날짜만 옮기면 표시명과 순서는 그대로다
        patchPeriod(id, sysAdminToken, """
                {"endDate": "%s"}
                """.formatted(today().plusMonths(4)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value(today().plusMonths(4).toString()))
                .andExpect(jsonPath("$.displayName").value("이번 방학"))
                .andExpect(jsonPath("$.displayOrder").value(30));

        // clearEndDate 단독이면 무기한이 되고, 신청 화면에도 그렇게 나간다
        patchPeriod(id, sysAdminToken, "{\"clearEndDate\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value((Object) null))
                .andExpect(jsonPath("$.expired").value(false));
        mockMvc.perform(get("/api/v1/request-periods")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(id) + ".endDate")
                        .value((Object) null));

        patchPeriod(SeedFixtures.UNKNOWN_ID, sysAdminToken, "{\"displayOrder\": 1}")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.detail").value("해당 사용 기간이 존재하지 않습니다."));
    }

    @Test
    void retiringAPeriodTakesItOutOfTheRequestFormAndNotOutOfTheAdminList() throws Exception {
        UUID id = insertPeriod("계절학기", today().plusMonths(1), "ACTIVE", 31);

        patchPeriod(id, sysAdminToken, "{\"status\": \"DISABLED\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));
        mockMvc.perform(get("/api/v1/request-periods")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(id)).isEmpty());
        mockMvc.perform(get("/api/v1/admin/request-periods")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(id) + ".status").value("DISABLED"))
                // 날짜는 아직 남아 있으므로 내린 것과 지난 것은 다른 상태다
                .andExpect(jsonPath(byId(id) + ".expired").value(false));

        patchPeriod(id, sysAdminToken, "{\"status\": \"ACTIVE\"}")
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/request-periods")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath(byId(id)).isNotEmpty());
    }

    /**
     * 학기와 방학과 무기한 사이에는 먼저랄 것이 없다. 순서는 계산되는 것이 아니라
     * 운영자가 말하는 것이고, 그 말이 신청 화면에 그대로 나타나야 한다.
     */
    @Test
    void displayOrderMovesThePeriodInTheRequestForm() throws Exception {
        UUID id = insertPeriod("추가 기간", today().plusMonths(5), "ACTIVE", 50);

        assertThat(offeredPeriodNames())
                .containsSubsequence("이번 학기", "이번 방학", "무기한 (교내 서비스)", "추가 기간");

        patchPeriod(id, sysAdminToken, "{\"displayOrder\": -50}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayOrder").value(-50));

        assertThat(offeredPeriodNames())
                .containsSubsequence("추가 기간", "이번 학기", "이번 방학", "무기한 (교내 서비스)");
    }

    @Test
    void patchAuditsRealChangesOnly() throws Exception {
        UUID id = insertPeriod("감사 확인 기간", today().plusMonths(2), "ACTIVE", 32);
        assertThat(auditCount("request-period.update", id)).isZero();

        patchPeriod(id, sysAdminToken, """
                {"displayName": "감사 확인 기간(개정)", "displayOrder": 33}
                """)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("감사 확인 기간(개정)"))
                .andExpect(jsonPath("$.displayOrder").value(33));
        assertThat(auditCount("request-period.update", id)).isEqualTo(1);

        // 같은 값을 다시 보내면 바뀐 것이 없다 → 200 이되 감사는 늘지 않는다
        patchPeriod(id, sysAdminToken, """
                {"displayName": "감사 확인 기간(개정)", "displayOrder": 33, "status": "ACTIVE"}
                """)
                .andExpect(status().isOk());
        assertThat(auditCount("request-period.update", id)).isEqualTo(1);

        // 이미 날짜가 없는 항목에 clearEndDate 를 다시 보내는 것도 무변경이다
        patchPeriod(id, sysAdminToken, "{\"clearEndDate\": true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endDate").value((Object) null));
        assertThat(auditCount("request-period.update", id)).isEqualTo(2);
        patchPeriod(id, sysAdminToken, "{\"clearEndDate\": true}")
                .andExpect(status().isOk());
        assertThat(auditCount("request-period.update", id)).isEqualTo(2);
    }

    @Test
    void readsAreSysTierAndWritesAreSysAdminOnly() throws Exception {
        UUID id = insertPeriod("권한 확인 기간", today().plusMonths(2), "ACTIVE", 34);
        String create = """
                {"name": "%s", "displayName": "권한 확인 생성"}
                """.formatted(uniqueName());

        // 읽기는 sys 계층 전체, 기관 관리자는 이 카탈로그에 닿지 못한다
        mockMvc.perform(get("/api/v1/admin/request-periods")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/request-periods")
                        .header("Authorization", "Bearer " + sysManagerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/request-periods")
                        .header("Authorization", "Bearer " + orgAdminToken))
                .andExpect(status().isForbidden());

        // 쓰기는 SYS_ADMIN 만 — 기간은 플랫폼 전체가 따르는 운영 상태다
        for (String token : List.of(sysViewerToken, sysManagerToken, orgAdminToken)) {
            mockMvc.perform(post("/api/v1/admin/request-periods")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(create))
                    .andExpect(status().isForbidden());
            patchPeriod(id, token, "{\"displayOrder\": 99}")
                    .andExpect(status().isForbidden());
        }

        // 인증이 없으면 401
        mockMvc.perform(get("/api/v1/admin/request-periods"))
                .andExpect(status().isUnauthorized());
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    /** Today in the timezone the offer decision is made in. */
    private static LocalDate today() {
        return LocalDate.now(ClockConfig.KST);
    }

    /** A name no other row in this suite carries. */
    private static String uniqueName() {
        return "arp-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A period row written the way an operator's earlier session left it. */
    private UUID insertPeriod(String displayName, LocalDate endDate, String status, int order) {
        return jdbcTemplate.queryForObject("""
                insert into request_period_presets (name, display_name, end_date, status,
                                                    display_order)
                values (?, ?, cast(? as date), cast(? as catalog_status), ?)
                returning public_id
                """, UUID.class, uniqueName(), displayName,
                endDate == null ? null : endDate.toString(), status, order);
    }

    private org.springframework.test.web.servlet.ResultActions patchPeriod(UUID id, String token,
            String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/admin/request-periods/{id}", id)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** The request form's period names, in the order it renders them. */
    private List<String> offeredPeriodNames() throws Exception {
        String body = mockMvc.perform(get("/api/v1/request-periods")
                        .header("Authorization", "Bearer " + sysViewerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$[*].displayName");
    }

    private static String byId(UUID id) {
        return "$[?(@.id == '%s')]".formatted(id);
    }

    private UUID publicIdOf(String name) {
        return jdbcTemplate.queryForObject(
                "select public_id from request_period_presets where name = ?", UUID.class, name);
    }

    private long auditCount(String action, UUID targetId) {
        return jdbcTemplate.queryForObject(
                "select count(*) from audit_logs where action = ? and target_id = ?",
                Long.class, action, targetId.toString());
    }

    private User ensureUser(String email, UserRole role) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email, "{test-no-login}", "기간테스트");
            user.setRole(role);
            user.setStatus(UserStatus.ACTIVE);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }
}
