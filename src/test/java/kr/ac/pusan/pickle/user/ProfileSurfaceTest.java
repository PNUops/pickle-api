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
    void movingFromAStudentPositionIsRefusedOnceThePositionIsSet() throws Exception {
        updateProfile(Map.of("position", "STUDENT_UNDERGRAD", "studentNo", "202012345",
                "departmentCode", "COMPUTER_SCIENCE")).andExpect(status().isOk());

        // Until 2026-08-27 this went through and dropped the 학번 with it. It is
        // the graduation case, and it is now an administrator's to make: the
        // drop is a write to a locked field that the request never mentions, so
        // allowing the 직책 half would have made the lock on 학번 bypassable.
        updateProfile(Map.of("position", "PROFESSOR"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'position')]").exists());

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.position").value("STUDENT_UNDERGRAD"))
                .andExpect(jsonPath("$.studentNo").value("202012345"));
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
    void aStudentNumberAddedLaterIsJudgedAgainstTheStoredPosition() throws Exception {
        // 직책 first, 학번 later — which the profile prompt produces when someone
        // fills half of it, closes it, and comes back.
        updateProfile(Map.of("position", "STUDENT_UNDERGRAD", "studentNo", "202012345",
                "departmentCode", "COMPUTER_SCIENCE")).andExpect(status().isOk());

        // Validation runs against the merge, not the request. Judged against
        // the request alone, a body carrying only 소속 is a profile with no 직책
        // and no 학번; judged against the merge it is the stored 학부생 with a
        // 학번, which is what it actually is.
        updateProfile(Map.of("departmentCode", "COMPUTER_SCIENCE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value("STUDENT_UNDERGRAD"))
                .andExpect(jsonPath("$.studentNo").value("202012345"));
    }

    @Test
    void aFieldStillEmptyCanBeFilledAfterTheOthersAreLocked() throws Exception {
        // Write-once is per field, not per profile. Someone who answered 직책
        // and closed the prompt has to be able to come back for the rest —
        // locking the whole profile on first save would strand them.
        updateProfile(Map.of("position", "PROFESSOR")).andExpect(status().isOk());
        updateProfile(Map.of("departmentOther", "정보컴퓨터공학부 부설연구소"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentOther").value("정보컴퓨터공학부 부설연구소"))
                .andExpect(jsonPath("$.profileComplete").value(true));
    }

    @Test
    void resendingAStoredValueIsNotTreatedAsAChange() throws Exception {
        updateProfile(Map.of("position", "STUDENT_UNDERGRAD", "studentNo", "202012345",
                "departmentCode", "COMPUTER_SCIENCE")).andExpect(status().isOk());

        // The console's profile modal opens prefilled and submits every field
        // it shows. Reading "present" as "changed" would refuse an edit that
        // only touches 이름, so what is refused is a different value.
        Map<String, Object> body = new HashMap<>();
        body.put("name", "같은 프로필 새 이름");
        body.put("position", "STUDENT_UNDERGRAD");
        body.put("studentNo", "  202012345  ");
        body.put("departmentCode", "COMPUTER_SCIENCE");
        updateProfile(body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("같은 프로필 새 이름"))
                .andExpect(jsonPath("$.studentNo").value("202012345"));
    }

    @Test
    void aStoredStudentNumberCannotBeReplacedOrCleared() throws Exception {
        updateProfile(Map.of("position", "STUDENT_UNDERGRAD", "studentNo", "202012345",
                "departmentCode", "COMPUTER_SCIENCE")).andExpect(status().isOk());

        // The reason the lock exists: a 학번 that is not the holder's stops
        // being reachable by editing.
        updateProfile(Map.of("studentNo", "202099999"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'studentNo')]").exists());

        Map<String, Object> cleared = new HashMap<>();
        cleared.put("studentNo", null);
        updateProfile(cleared)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'studentNo')]").exists());

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.studentNo").value("202012345"));
    }

    @Test
    void anExplicitNullNoLongerClearsAStoredField() throws Exception {
        updateProfile(Map.of("position", "PROFESSOR", "departmentCode", "COMPUTER_SCIENCE"))
                .andExpect(status().isOk());

        // Clearing used to be the way out of a wrong answer. Under write-once
        // it is the way around the lock — clear, then set something else — so
        // it is refused with the same message, and correcting a wrong value is
        // the administrator endpoint's job.
        Map<String, Object> body = new HashMap<>();
        body.put("departmentCode", null);
        updateProfile(body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'departmentCode')]").exists());
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
    void clearingAStoredPositionIsRefusedRatherThanCascading() throws Exception {
        updateProfile(Map.of("position", "STUDENT_UNDERGRAD", "studentNo", "202012345",
                "departmentCode", "COMPUTER_SCIENCE")).andExpect(status().isOk());

        // This used to clear the 학번 along with the 직책 it belonged to, without
        // the request mentioning 학번 at all. That cascade is why clearing 직책
        // had to be refused too: it was a write to a locked field through a
        // field that was not the locked one.
        Map<String, Object> body = new HashMap<>();
        body.put("position", null);
        updateProfile(body)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'position')]").exists());

        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.studentNo").value("202012345"));
    }

    @Test
    void aFreeTextDepartmentBesideACatalogueCodeIsRefused() throws Exception {
        // 소속 has two shapes and they are alternatives: a student picks a code,
        // everyone else writes it, and only the unlisted-학과 case carries both
        // (with the OTHER code). chk_users_department_other refuses the rest, so
        // saying it here is the difference between a field error and a 500.
        updateProfile(Map.of("position", "PROFESSOR", "departmentCode", "COMPUTER_SCIENCE",
                "departmentOther", "부설연구소"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'departmentOther')]").exists());
    }

    @Test
    void aStudentWhoseDepartmentIsUnlistedCarriesBoth() throws Exception {
        updateProfile(Map.of("position", "STUDENT_UNDERGRAD", "studentNo", "202012345",
                "departmentCode", "OTHER", "departmentOther", "융합학부"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentCode").value("OTHER"))
                .andExpect(jsonPath("$.departmentOther").value("융합학부"))
                .andExpect(jsonPath("$.profileComplete").value(true));
    }

    @Test
    void aWrittenDepartmentAloneCompletesTheProfile() throws Exception {
        // The shape a 교수 or 직원 produces: no catalogue code at all. Requiring
        // the code would leave every one of them permanently incomplete and the
        // prompt would reopen every session.
        updateProfile(Map.of("position", "PROFESSOR", "departmentOther", "정보컴퓨터공학부"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentCode").doesNotExist())
                .andExpect(jsonPath("$.departmentOther").value("정보컴퓨터공학부"))
                .andExpect(jsonPath("$.profileComplete").value(true));
    }

    @Test
    void aWrittenDepartmentIsLockedLikeTheRest() throws Exception {
        // The fourth locked field, and the one the first pass left unchecked.
        updateProfile(Map.of("position", "PROFESSOR", "departmentOther", "부설연구소"))
                .andExpect(status().isOk());

        updateProfile(Map.of("departmentOther", "다른 연구소"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'departmentOther')]").exists());

        Map<String, Object> cleared = new HashMap<>();
        cleared.put("departmentOther", null);
        updateProfile(cleared)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'departmentOther')]").exists());
    }

    @Test
    void anAccountWhoseDepartmentLeftTheCatalogueCanStillChangeItsName() throws Exception {
        // The catalogue is a file with a host override so a yearly
        // reorganisation can change it, which means a stored code can stop
        // being listed. Judged against the merge, that code reaches validation
        // on every write — so without the exemption this account would get a
        // 422 on 소속 for a request that only changes 이름, and under write-once
        // it could neither pick another code nor clear the old one. Before the
        // lock it could pick something else and move on.
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        user.setProfile(UserPosition.PROFESSOR, null, "DEPARTMENT_THAT_CLOSED", null);
        userRepository.saveAndFlush(user);

        updateProfile(Map.of("name", "학과가 없어진 사람"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("학과가 없어진 사람"))
                .andExpect(jsonPath("$.departmentCode").value("DEPARTMENT_THAT_CLOSED"))
                // Unresolvable, so the code stands in for the name.
                .andExpect(jsonPath("$.departmentName").value("DEPARTMENT_THAT_CLOSED"));

        // A code the request introduces is still checked.
        updateProfile(Map.of("name", "또 바꿈", "departmentCode", "ALSO_NOT_A_DEPARTMENT"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'departmentCode')]").exists());
    }

    @Test
    void aNonStudentHoldingACatalogueCodeCanStillSave() throws Exception {
        // The shape every pre-v0.46.0 profile has: 소속 was required for
        // everyone, 교수 included. Refusing a code beside a non-student
        // position would refuse rows that exist on live data today, on a
        // request that only changes 이름.
        User user = userRepository.findByEmail(EMAIL).orElseThrow();
        user.setProfile(UserPosition.RESEARCHER, null, "COMPUTER_SCIENCE", null);
        userRepository.saveAndFlush(user);

        updateProfile(Map.of("name", "코드를 든 연구원"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.departmentCode").value("COMPUTER_SCIENCE"));

        // What it cannot do is move to the written shape: the stored code is
        // locked and the two together are refused. That is the administrator's
        // to do, in one request that clears the code and writes the value.
        updateProfile(Map.of("departmentOther", "부설연구소"))
                .andExpect(status().isUnprocessableContent());
    }

    private org.springframework.test.web.servlet.ResultActions updateProfile(Map<String, ?> body)
            throws Exception {
        return mockMvc.perform(put("/api/v1/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
