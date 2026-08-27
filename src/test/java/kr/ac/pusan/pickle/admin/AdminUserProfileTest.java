package kr.ac.pusan.pickle.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import kr.ac.pusan.pickle.audit.AuditService;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.support.SeedFixtures;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserPosition;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

/**
 * The administrator half of the write-once profile lock
 * ({@code PATCH /admin/users/{userId}/profile}).
 *
 * <p>Without it the lock is the trap {@code V89} described when it declined a
 * unique constraint on 학번: the first value entered is permanent, typo
 * included, with nobody able to correct it. These tests are what says the way
 * back exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminUserProfileTest {

    private static final String TARGET = "aup.target@pusan.ac.kr";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String sysAdminToken;
    private User target;

    @BeforeEach
    void setUp() {
        sysAdminToken = jwtService.createAccessToken(
                userRepository.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow());
        target = userRepository.findByEmail(TARGET).orElseGet(() -> {
            User user = new User(TARGET, "$2a$12$C6UzMDM.H6dfI/f/IKcEeO7uHhZ8mCEyXbNP9qhrPQicvBSl2Fx16",
                    "정정대상");
            user.setStatus(UserStatus.ACTIVE);
            user.setRole(UserRole.USER);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
        // The state the lock produces: a 학번 the holder can no longer touch.
        target.setProfile(UserPosition.STUDENT_UNDERGRAD, "202012345", "COMPUTER_SCIENCE", null);
        target = userRepository.saveAndFlush(target);
    }

    @Test
    void theDetailCarriesTheProfileTheAdministratorHasToRead() throws Exception {
        mockMvc.perform(get("/api/v1/admin/users/" + target.getPublicId())
                        .header("Authorization", "Bearer " + sysAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value("STUDENT_UNDERGRAD"))
                .andExpect(jsonPath("$.studentNo").value("202012345"))
                .andExpect(jsonPath("$.departmentCode").value("COMPUTER_SCIENCE"))
                // Resolved, not stored: the screen shows a 학과 name.
                .andExpect(jsonPath("$.departmentName").value("정보컴퓨터공학부"));
    }

    @Test
    void aMistypedStudentNumberCanBeCorrected() throws Exception {
        updateProfile(Map.of("studentNo", "202054321", "reason", "본인 확인 후 학번 정정"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentNo").value("202054321"));

        assertThat(userRepository.findByEmail(TARGET).orElseThrow().getStudentNo())
                .isEqualTo("202054321");
    }

    @Test
    void aValueEnteredByMistakeCanBeCleared() throws Exception {
        // Clearing is refused on the holder's path, because clear-then-set is
        // the way around the lock. Here it is the point: a 학번 that should
        // never have been on this account has to be removable, not only
        // replaceable.
        Map<String, Object> body = new HashMap<>();
        body.put("departmentCode", null);
        updateProfile(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentCode").doesNotExist());
    }

    @Test
    void graduationIsTheAdministratorsToMakeAndTheDropIsRecorded() throws Exception {
        // The 학부생-to-교수 move the holder can no longer make. The 학번 goes
        // with it without the request mentioning 학번, so the audit entry has to
        // say that it did.
        updateProfile(Map.of("position", "PROFESSOR", "reason", "졸업 후 임용"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value("PROFESSOR"))
                .andExpect(jsonPath("$.studentNo").doesNotExist());

        Map<String, Object> entry = latestAuditEntry();
        assertThat(String.valueOf(entry.get("action"))).isEqualTo(AuditService.USER_PROFILE_UPDATE);
        assertThat(String.valueOf(entry.get("detail")))
                .contains("\"studentNoDroppedByPositionChange\": \"true\"")
                .contains("\"previousPosition\": \"STUDENT_UNDERGRAD\"")
                // Never the value itself — the audit log is not a second place
                // to keep a 학번.
                .doesNotContain("202012345");
    }

    @Test
    void theSameValueRulesApply() throws Exception {
        updateProfile(Map.of("departmentCode", "NO_SUCH_DEPARTMENT"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'departmentCode')]").exists());

        // An administrator may correct a profile, not store a shape the CHECK
        // constraint refuses.
        updateProfile(Map.of("departmentCode", "COMPUTER_SCIENCE", "departmentOther", "부설연구소"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'departmentOther')]").exists());
    }

    @Test
    void anEmptyBodyIsRefused() throws Exception {
        updateProfile(Map.of("reason", "사유만 보냄"))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void theHolderIsToldTheirProfileWasChanged() throws Exception {
        updateProfile(Map.of("studentNo", "202054321", "reason", "정정"))
                .andExpect(status().isOk());

        Integer notices = jdbcTemplate.queryForObject(
                "select count(*) from notifications where user_id = ? and event = ?",
                Integer.class, target.getId(), "account.profile.updated");
        // 직책·학번·소속 cannot be changed back by the holder, so being told is
        // the only way they find out what happened to their own identifier.
        assertThat(notices).isEqualTo(1);
    }

    @Test
    void aNonSysAdminCannotReachIt() throws Exception {
        User orgAdmin = ensureUser("aup.orgadmin@pusan.ac.kr", UserRole.ORG_ADMIN, "기관관리자");
        // 학번 identifies a real person and a wrong one is not the holder's to
        // fix, so this stays with the role that holds every other write on
        // another account. Widening it later is a decision, not an oversight.
        mockMvc.perform(patch("/api/v1/admin/users/" + target.getPublicId() + "/profile")
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(orgAdmin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"studentNo\":\"202099999\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void theOrgTierDoesNotSeeTheProfileOnTheDetail() throws Exception {
        // This endpoint admits ORG_VIEWER, which an organisation grants to
        // ANOTHER organisation's staff, and it is not org-scoped — so filling
        // 학번 in for the org tier would hand every organisation's staff every
        // account's identifier. Drawn at the line the audit log draws.
        User orgAdmin = ensureUser("aup.orgadmin@pusan.ac.kr", UserRole.ORG_ADMIN, "기관관리자");
        mockMvc.perform(get("/api/v1/admin/users/" + target.getPublicId())
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(orgAdmin)))
                .andExpect(status().isOk())
                // The row is still readable; the identifier is not.
                .andExpect(jsonPath("$.email").value(TARGET))
                .andExpect(jsonPath("$.studentNo").doesNotExist())
                .andExpect(jsonPath("$.position").doesNotExist())
                .andExpect(jsonPath("$.departmentCode").doesNotExist())
                .andExpect(jsonPath("$.departmentName").doesNotExist())
                .andExpect(jsonPath("$.departmentOther").doesNotExist());
    }

    @Test
    void theSysTierReadsIt() throws Exception {
        // SYS_VIEWER reads the audit log's ground already, so the line is the
        // tier and not the one role that can write.
        User sysViewer = ensureUser("aup.sysviewer@pusan.ac.kr", UserRole.SYS_VIEWER, "시스템열람자");
        mockMvc.perform(get("/api/v1/admin/users/" + target.getPublicId())
                        .header("Authorization", "Bearer " + jwtService.createAccessToken(sysViewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentNo").value("202012345"));
    }

    @Test
    void theLegacyShapeCanBeMovedToWrittenDepartmentInOneRequest() throws Exception {
        // The account the holder cannot fix: a non-student carrying a
        // catalogue code, which is what every pre-v0.46.0 profile looks like.
        // Both halves in one request, because clearing first is not a state
        // this endpoint has to pass through.
        target.setProfile(UserPosition.RESEARCHER, null, "COMPUTER_SCIENCE", null);
        target = userRepository.saveAndFlush(target);

        Map<String, Object> body = new HashMap<>();
        body.put("departmentCode", null);
        body.put("departmentOther", "정보컴퓨터공학부 부설연구소");
        body.put("reason", "학과 코드에서 자유 입력으로 이행");
        updateProfile(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentCode").doesNotExist())
                .andExpect(jsonPath("$.departmentOther").value("정보컴퓨터공학부 부설연구소"));
    }

    @Test
    void movingToAStudentPositionWithoutANumberIsRefused() throws Exception {
        // The rule holds on this path too: an administrator may correct a
        // profile, not store a shape chk_users_student_no refuses.
        target.setProfile(UserPosition.PROFESSOR, null, "COMPUTER_SCIENCE", null);
        target = userRepository.saveAndFlush(target);

        updateProfile(Map.of("position", "STUDENT_UNDERGRAD", "reason", "학적 정정"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'studentNo')]").exists());
    }

    private User ensureUser(String email, UserRole role, String name) {
        return userRepository.findByEmail(email).orElseGet(() -> {
            User user = new User(email,
                    "$2a$12$C6UzMDM.H6dfI/f/IKcEeO7uHhZ8mCEyXbNP9qhrPQicvBSl2Fx16", name);
            user.setStatus(UserStatus.ACTIVE);
            user.setRole(role);
            user.setEmailVerifiedAt(Instant.now());
            return userRepository.save(user);
        });
    }

    private Map<String, Object> latestAuditEntry() {
        return jdbcTemplate.queryForMap(
                "select action, detail from audit_logs where action = ? order by id desc limit 1",
                AuditService.USER_PROFILE_UPDATE);
    }

    private ResultActions updateProfile(Map<String, ?> body) throws Exception {
        return mockMvc.perform(patch("/api/v1/admin/users/" + target.getPublicId() + "/profile")
                .header("Authorization", "Bearer " + sysAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
