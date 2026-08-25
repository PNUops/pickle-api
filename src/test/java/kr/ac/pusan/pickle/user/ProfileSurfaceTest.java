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

    private org.springframework.test.web.servlet.ResultActions updateProfile(Map<String, ?> body)
            throws Exception {
        return mockMvc.perform(put("/api/v1/me/profile")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
