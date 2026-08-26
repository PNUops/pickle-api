package kr.ac.pusan.pickle.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import kr.ac.pusan.pickle.identity.IdentityProvider;
import kr.ac.pusan.pickle.identity.UserIdentity;
import kr.ac.pusan.pickle.identity.UserIdentityRepository;
import kr.ac.pusan.pickle.security.JwtService;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * The profile as the console reads and writes it: what {@code GET /me} carries
 * for the profile gate, and what {@code PUT /me/profile} accepts.
 *
 * <p>An account created before V89 has no profile at all, which is the state
 * every existing account is in, so that is where these start.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ProfileSurfaceTest {

    private static final String EMAIL = "profile.tester@pusan.ac.kr";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserIdentityRepository identityRepository;

    @Autowired
    private JwtService jwtService;

    private String token;

    @BeforeEach
    void createAccountWithoutAProfile() {
        userRepository.findByEmail(EMAIL).ifPresent(existing -> {
            // deleteAll rather than the derived deleteByUserId: the derived
            // delete needs an ambient transaction, which a @BeforeEach has not
            // got.
            identityRepository.deleteAll(identityRepository.findByUserIdOrderByLinkedAtAsc(existing.getId()));
            userRepository.delete(existing);
            userRepository.flush();
        });
        User user = new User(EMAIL, "$2a$12$C6UzMDM.H6dfI/f/IKcEeO7uHhZ8mCEyXbNP9qhrPQicvBSl2Fx16", "프로필");
        user.setStatus(UserStatus.ACTIVE);
        token = jwtService.createAccessToken(userRepository.saveAndFlush(user));
    }

    @Test
    void anAccountWithoutAProfileReportsItAsIncomplete() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileComplete").value(false))
                .andExpect(jsonPath("$.position").doesNotExist())
                .andExpect(jsonPath("$.hasPassword").value(true))
                .andExpect(jsonPath("$.identities").isEmpty());
    }

    @Test
    void fillingTheProfileInCompletesItAndResolvesTheDepartmentName() throws Exception {
        updateProfile(Map.of("position", "STUDENT_GRADUATE", "studentNo", "202512345",
                "departmentCode", "COMPUTER_SCIENCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileComplete").value(true))
                .andExpect(jsonPath("$.position").value("STUDENT_GRADUATE"))
                .andExpect(jsonPath("$.studentNo").value("202512345"))
                .andExpect(jsonPath("$.departmentCode").value("COMPUTER_SCIENCE"))
                // Resolved on read, so a renamed department needs no migration.
                .andExpect(jsonPath("$.departmentName").value("정보컴퓨터공학부"));
    }

    @Test
    void movingFromAStudentPositionDropsTheStudentNumber() throws Exception {
        updateProfile(Map.of("position", "STUDENT_UNDERGRAD", "studentNo", "202012345",
                "departmentCode", "COMPUTER_SCIENCE")).andExpect(status().isOk());
        // The number is not carried over: it was required by the old position
        // and means nothing under the new one. Sending it anyway is what the
        // console does if it forgets to clear its own field.
        updateProfile(Map.of("position", "PROFESSOR", "studentNo", "202012345",
                "departmentCode", "COMPUTER_SCIENCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentNo").doesNotExist())
                .andExpect(jsonPath("$.profileComplete").value(true));
    }

    @Test
    void aStudentWithoutANumberIsRefusedByField() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("position", "STUDENT_UNDERGRAD");
        body.put("studentNo", null);
        body.put("departmentCode", "COMPUTER_SCIENCE");
        updateProfile(body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'studentNo')]").exists());
    }

    @Test
    void anUnknownDepartmentIsRefusedByField() throws Exception {
        updateProfile(Map.of("position", "PROFESSOR", "departmentCode", "NO_SUCH_DEPARTMENT"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'departmentCode')]").exists());
    }

    @Test
    void aLinkedIdentityShowsUpOnTheProfile() throws Exception {
        long userId = userRepository.findByEmail(EMAIL).orElseThrow().getId();
        identityRepository.saveAndFlush(new UserIdentity(userId, IdentityProvider.GOOGLE,
                "google-subject-1", EMAIL, "pusan.ac.kr", java.time.Instant.now()));

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.identities[0].provider").value("GOOGLE"))
                .andExpect(jsonPath("$.identities[0].email").value(EMAIL));

        assertThat(identityRepository.findByProviderAndSubject(IdentityProvider.GOOGLE, "google-subject-1"))
                .isPresent();
    }

    @Test
    void sendingOnlyTheNameLeavesTheProfileAlone() throws Exception {
        updateProfile(Map.of("position", "STUDENT_UNDERGRAD", "studentNo", "202012345",
                "departmentCode", "COMPUTER_SCIENCE")).andExpect(status().isOk());

        // The account screen changes the display name on its own. A full
        // replace would read the three absent fields as null and wipe a
        // profile the user never touched, with a 200 and no field error to
        // show for it.
        updateProfile(Map.of("name", "새 이름"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("새 이름"))
                .andExpect(jsonPath("$.position").value("STUDENT_UNDERGRAD"))
                .andExpect(jsonPath("$.studentNo").value("202012345"))
                .andExpect(jsonPath("$.departmentCode").value("COMPUTER_SCIENCE"))
                .andExpect(jsonPath("$.profileComplete").value(true));
    }

    @Test
    void changingOnlyThePositionIsJudgedAgainstTheStoredStudentNumber() throws Exception {
        updateProfile(Map.of("position", "STUDENT_UNDERGRAD", "studentNo", "202012345",
                "departmentCode", "COMPUTER_SCIENCE")).andExpect(status().isOk());

        // Validation runs against the merge, not the request. Judged against
        // the request alone this is a student position with no 학번 and would
        // be a 422 the console could not clear without resending a field it
        // was not changing.
        updateProfile(Map.of("position", "STUDENT_GRADUATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value("STUDENT_GRADUATE"))
                .andExpect(jsonPath("$.studentNo").value("202012345"));
    }

    @Test
    void anExplicitNullClearsTheField() throws Exception {
        updateProfile(Map.of("position", "PROFESSOR", "departmentCode", "COMPUTER_SCIENCE"))
                .andExpect(status().isOk());

        Map<String, Object> body = new HashMap<>();
        body.put("departmentCode", null);
        updateProfile(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentCode").doesNotExist())
                // Not a defect: the profile is optional, so an emptied field is
                // an ordinary state and the console only prompts about it.
                .andExpect(jsonPath("$.profileComplete").value(false));
    }

    @Test
    void anEmptyBodyIsRefused() throws Exception {
        updateProfile(Map.of()).andExpect(status().isUnprocessableContent());
    }

    @Test
    void theNameCannotBeCleared() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("name", null);
        updateProfile(body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());

        updateProfile(Map.of("name", "   "))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());
    }

    @Test
    void aStudentNumberWithNoPositionIsNotStored() throws Exception {
        // Both the format check and the normalisation key off the position, and
        // chk_users_student_no is an implication whose first disjunct is
        // "position is null" — so with 직책 optional this string would reach the
        // column having passed no rule at all. It is dropped instead, exactly
        // as a 교수's would be.
        updateProfile(Map.of("studentNo", "<script>x</script>"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentNo").doesNotExist());

        assertThat(userRepository.findByEmail(EMAIL).orElseThrow().getStudentNo()).isNull();
    }

    @Test
    void clearingThePositionAlsoClearsTheStudentNumberItBelongedTo() throws Exception {
        updateProfile(Map.of("position", "STUDENT_UNDERGRAD", "studentNo", "202012345",
                "departmentCode", "COMPUTER_SCIENCE")).andExpect(status().isOk());

        // The 학번 is not in this request at all, and it still goes. It belonged
        // to the position being cleared, and a 학번 with no 직책 to hang off is
        // the unvalidatable state the CHECK cannot refuse. Same drop as the
        // 학부생-to-교수 switch, reached from the other direction.
        Map<String, Object> body = new HashMap<>();
        body.put("position", null);
        updateProfile(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").doesNotExist())
                .andExpect(jsonPath("$.studentNo").doesNotExist())
                // 소속 학과 was not mentioned either, and unlike 학번 it does not
                // depend on the position, so it stays.
                .andExpect(jsonPath("$.departmentCode").value("COMPUTER_SCIENCE"));
    }

    private org.springframework.test.web.servlet.ResultActions updateProfile(Map<String, ?> body)
            throws Exception {
        return mockMvc.perform(put("/api/v1/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
