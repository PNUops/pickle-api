package kr.ac.pusan.pickle.notice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminNoticeDetailTest {
    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @Autowired private UserRepository users;
    @Autowired private NoticeRepository notices;
    @Autowired private ObjectMapper mapper;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private Notice scheduled;
    private Notice ended;
    private String adminToken;

    @BeforeEach
    void setup() {
        User author = users.findByEmail(SeedFixtures.SYSADMIN_EMAIL).orElseThrow();
        adminToken = jwt.createAccessToken(author);
        jdbc.update("delete from notices");
        Instant now = Instant.now();
        scheduled = notices.saveAndFlush(new Notice(author.getId(), "예정 공지",
                "<b>그대로 보이는 평문</b>", false, true, now.plus(1, ChronoUnit.DAYS), null));
        ended = notices.saveAndFlush(new Notice(author.getId(), "종료 공지", "보존된 본문",
                false, false, now.minus(2, ChronoUnit.DAYS), now.minus(1, ChronoUnit.DAYS)));
    }

    @Test
    void everyAdministrativeRoleReadsScheduledAndEndedDetails() throws Exception {
        for (UserRole role : UserRole.values()) {
            if (role == UserRole.USER) continue;
            String token = token(role);
            mvc.perform(get(path(scheduled.getPublicId())).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(scheduled.getPublicId().toString()))
                    .andExpect(jsonPath("$.body").value("<b>그대로 보이는 평문</b>"))
                    .andExpect(jsonPath("$.active").value(false))
                    .andExpect(jsonPath("$.createdByName").isNotEmpty())
                    .andExpect(jsonPath("$.images").isArray());
            mvc.perform(get(path(ended.getPublicId())).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(ended.getPublicId().toString()))
                    .andExpect(jsonPath("$.endsAt").isNotEmpty())
                    .andExpect(jsonPath("$.active").value(false));
        }
    }

    @Test
    void administrativeDetailDoesNotWidenPublicVisibilityOrWriting() throws Exception {
        mvc.perform(get(path(scheduled.getPublicId()))).andExpect(status().isUnauthorized());
        mvc.perform(get(path(scheduled.getPublicId())).header("Authorization", "Bearer " + token(UserRole.USER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notices/" + scheduled.getPublicId()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/notices/" + ended.getPublicId())
                .header("Authorization", "Bearer " + token(UserRole.USER)))
                .andExpect(status().isNotFound());
        mvc.perform(get(path(UUID.randomUUID())).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
        mvc.perform(patch(path(scheduled.getPublicId())).header("Authorization", "Bearer " + token(UserRole.ORG_VIEWER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"거절할 변경\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void explicitDetailKeepsTheEditedTargetAfterItMovesOffTheListPage() throws Exception {
        mvc.perform(get("/api/v1/admin/notices?page=0&size=1")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(scheduled.getPublicId().toString()));
        String earlier = Instant.now().minus(10, ChronoUnit.DAYS).toString();
        mvc.perform(patch(path(scheduled.getPublicId())).header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("startsAt", earlier, "title", "수정된 같은 공지"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(scheduled.getPublicId().toString()));
        mvc.perform(get("/api/v1/admin/notices?page=0&size=1")
                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(ended.getPublicId().toString()))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get(path(scheduled.getPublicId())).header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("수정된 같은 공지"))
                .andExpect(jsonPath("$.body").value("<b>그대로 보이는 평문</b>"));
    }

    private String token(UserRole role) {
        String email = "notice-detail-" + role.name().toLowerCase(java.util.Locale.ROOT) + "@example.test";
        User user = users.findByEmail(email).orElseGet(() -> new User(email, "{noop}unused", "합성 관리자"));
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        return jwt.createAccessToken(users.saveAndFlush(user));
    }

    private static String path(UUID id) { return "/api/v1/admin/notices/" + id; }
}
