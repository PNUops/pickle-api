package kr.ac.pusan.pickle.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.User;
import kr.ac.pusan.pickle.user.UserRepository;
import kr.ac.pusan.pickle.user.UserStatus;
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
 * Signup answers the same 202 whether or not the address is already
 * registered, so nothing may reveal that it is. The defence is an ordering
 * one: every rejection that depends only on the shape of the request has to
 * happen <b>before</b> the address is looked up, or the choice between "422"
 * and "202" becomes the oracle the uniform response was meant to remove.
 *
 * <p>Adding the profile fields put a new rule in that window — 학번 is required
 * for a student position, and 소속 has to be a department the catalogue knows —
 * and neither can be expressed as bean validation, so both run in the service
 * layer where the ordering is a matter of which line comes first. That is
 * exactly the kind of check that drifts below the lookup during a later edit,
 * so it is pinned here rather than left to review.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class SignupValidationOrderTest {

    private static final String REGISTERED = "order.registered@pusan.ac.kr";
    private static final String UNKNOWN = "order.unknown@pusan.ac.kr";
    private static final String PASSWORD = "Corr3ct-horse-battery!";

    private static final Object FULL_CONSENTS = List.of(
            Map.of("docType", "TERMS_OF_SERVICE", "version", 1),
            Map.of("docType", "PRIVACY_POLICY", "version", 1));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void seedARegisteredAddress() {
        if (userRepository.findByEmail(REGISTERED).isEmpty()) {
            User user = new User(REGISTERED, "$2a$12$C6UzMDM.H6dfI/f/IKcEeO7uHhZ8mCEyXbNP9qhrPQicvBSl2Fx16",
                    "이미가입");
            user.setStatus(UserStatus.ACTIVE);
            userRepository.saveAndFlush(user);
        }
    }

    @Test
    void aStudentWithoutAStudentNumberIsRefusedBeforeTheAddressIsLookedUp() throws Exception {
        // The same broken profile on a registered and an unknown address. If the
        // profile rule ran after the lookup, the registered one would answer 202
        // (the uniform "we have taken your signup") and the unknown one 422 —
        // and the difference would name every account on the platform.
        signup(REGISTERED, Map.of("position", "STUDENT_UNDERGRAD", "studentNo", ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'studentNo')]").exists());
        signup(UNKNOWN, Map.of("position", "STUDENT_UNDERGRAD", "studentNo", ""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'studentNo')]").exists());
    }

    @Test
    void anUnknownDepartmentIsRefusedBeforeTheAddressIsLookedUp() throws Exception {
        signup(REGISTERED, Map.of("departmentCode", "NO_SUCH_DEPARTMENT"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'departmentCode')]").exists());
        signup(UNKNOWN, Map.of("departmentCode", "NO_SUCH_DEPARTMENT"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'departmentCode')]").exists());
    }

    @Test
    void aDepartmentSentAsBothShapesIsRefusedBeforeTheAddressIsLookedUp() throws Exception {
        // The shape the CHECK constraint refuses, so failing to catch it here
        // is a 500 at flush rather than a field error. And it has to be caught
        // before the lookup like every other request-shape rejection, or the
        // validation order becomes the oracle the uniform 202 exists to remove.
        Map<String, Object> both = Map.of("position", "PROFESSOR",
                "departmentCode", "COMPUTER_SCIENCE", "departmentOther", "부설연구소");
        // Both addresses, because the point is that the rejection does not
        // depend on which one it is — that is the invariant this class defends.
        signup(REGISTERED, both, "10.96.0.11")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'departmentOther')]").exists());
        signup(UNKNOWN, both, "10.96.0.11")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[?(@.field == 'departmentOther')]").exists());
    }

    @Test
    void aWrittenDepartmentAloneIsAcceptedOnEitherAddress() throws Exception {
        // The shape a 교수 produces. Signup accepted the field from the start
        // of this round but no test had ever sent it.
        // The base body carries a 학과 code, and the two shapes together are
        // refused, so the code has to be cleared explicitly — which is exactly
        // what the console does for a non-student position.
        Map<String, Object> written = new java.util.HashMap<>();
        written.put("position", "PROFESSOR");
        written.put("studentNo", null);
        written.put("departmentCode", null);
        written.put("departmentOther", "정보컴퓨터공학부 부설연구소");
        signup(UNKNOWN, written, "10.96.0.12").andExpect(status().isAccepted());
    }

    @Test
    void aWellFormedSignupStillAnswersTheSameOnEitherAddress() throws Exception {
        // The other half of the invariant: once the shape is valid, both
        // addresses get the identical 202.
        signup(REGISTERED, Map.of()).andExpect(status().isAccepted());
        signup(UNKNOWN, Map.of()).andExpect(status().isAccepted());
    }

    @Test
    void aNonStudentNeedsNoStudentNumber() throws Exception {
        signup("order.professor@pusan.ac.kr",
                Map.of("position", "PROFESSOR", "studentNo", ""))
                .andExpect(status().isAccepted());
    }

    @Test
    void aSignupWithNoProfileAtAllIsAccepted() throws Exception {
        // 직책 and 소속 학과 sent as explicit nulls (v0.46.0), which is what an
        // absent JSON key binds to on a record anyway. The console collects them
        // after the account exists. The ordering rules above still hold, they
        // simply have nothing to reject.
        Map<String, Object> none = new LinkedHashMap<>();
        none.put("position", null);
        none.put("studentNo", null);
        none.put("departmentCode", null);
        signup(REGISTERED, none).andExpect(status().isAccepted());
        signup("order.noprofile@pusan.ac.kr", none).andExpect(status().isAccepted());

        User created = userRepository.findByEmail("order.noprofile@pusan.ac.kr").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(created.getPosition()).isNull();
        org.assertj.core.api.Assertions.assertThat(created.getDepartmentCode()).isNull();
        org.assertj.core.api.Assertions.assertThat(created.isProfileComplete()).isFalse();
    }

    @Test
    void aStudentNumberWithNoPositionIsDroppedRatherThanStored() throws Exception {
        // No position means no rule looked at this value and the CHECK cannot
        // either, so it must not reach the column.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("position", null);
        body.put("studentNo", "not-a-number!!");
        body.put("departmentCode", null);
        signup("order.orphan@pusan.ac.kr", body).andExpect(status().isAccepted());

        User created = userRepository.findByEmail("order.orphan@pusan.ac.kr").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(created.getStudentNo()).isNull();
    }

    private org.springframework.test.web.servlet.ResultActions signup(String email,
            Map<String, ?> overrides) throws Exception {
        return signup(email, overrides, "10.96.0.7");
    }

    /**
     * As above, from a named client address.
     *
     * <p>The per-IP signup window is shared, so a case that posts its own
     * handful of signups takes its own address. Raising the limit to fit the
     * suite would weaken the thing the limit is for.
     */
    private org.springframework.test.web.servlet.ResultActions signup(String email,
            Map<String, ?> overrides, String clientAddr) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>(Map.of(
                "email", email,
                "password", PASSWORD,
                "name", "순서검사",
                "position", "STUDENT_UNDERGRAD",
                "studentNo", "202012345",
                "departmentCode", "COMPUTER_SCIENCE",
                "consents", FULL_CONSENTS));
        body.putAll(overrides);
        return mockMvc.perform(post("/api/v1/auth/signup")
                .with(request -> {
                    // Its own client address: these cases post several signups and
                    // the per-IP window is shared with every other test class.
                    request.setRemoteAddr(clientAddr);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
