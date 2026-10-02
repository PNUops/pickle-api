package kr.ac.pusan.pickle.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.contains;

import java.time.Instant;
import java.util.UUID;
import kr.ac.pusan.pickle.orgs.Org;
import kr.ac.pusan.pickle.orgs.OrgRepository;
import kr.ac.pusan.pickle.orgs.OrgStatus;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class AdminOrgOperationsTest {

    @Autowired private MockMvc mvc;
    @Autowired private OrgRepository orgRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtService jwtService;

    private Org org;
    private Org other;
    private User admin;
    private User manager;
    private User viewer;

    @BeforeEach
    void setUp() {
        org = orgRepository.saveAndFlush(new Org("명단 " + UUID.randomUUID(), null));
        other = orgRepository.saveAndFlush(new Org("다른 명단 " + UUID.randomUUID(), null));
        admin = account(UserRole.ORG_ADMIN, "기관 관리자");
        manager = account(UserRole.ORG_MANAGER, "기관 운영자");
        viewer = account(UserRole.ORG_VIEWER, "기관 열람자");
        grant(admin, org, UserRole.ORG_ADMIN);
        grant(manager, org, UserRole.ORG_MANAGER);
        grant(viewer, org, UserRole.ORG_VIEWER);
    }

    @Test
    void allSixAdminRolesReadTheirPermittedOrganisation() throws Exception {
        read(org, admin).andExpect(status().isOk());
        read(org, manager).andExpect(status().isOk());
        read(org, viewer).andExpect(status().isOk());
        for (UserRole role : new UserRole[] {UserRole.SYS_VIEWER, UserRole.SYS_MANAGER, UserRole.SYS_ADMIN}) {
            read(org, account(role, "시스템 읽기"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.members.length()").value(3));
        }
        read(org, account(UserRole.USER, "일반 사용자")).andExpect(status().isForbidden());
        mvc.perform(get(path(org))).andExpect(status().isUnauthorized());
    }

    @Test
    void organisationRoleRatherThanHighestRoleDefinesTheRoster() throws Exception {
        grant(admin, other, UserRole.ORG_VIEWER);
        read(other, admin)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].userId").value(admin.getPublicId().toString()))
                .andExpect(jsonPath("$.members[0].role").value("ORG_VIEWER"))
                .andExpect(jsonPath("$.activeAdminCount").value(0))
                .andExpect(jsonPath("$.currentMailRecipientCount").value(0));
        read(other, manager).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/orgs/" + SeedFixtures.UNKNOWN_ID + "/operations")
                        .header("Authorization", bearer(manager)))
                .andExpect(status().isNotFound());
    }

    @Test
    void disabledInstitutionsAndInactiveRoleHoldersRemainVisible() throws Exception {
        org.setStatus(OrgStatus.DISABLED);
        orgRepository.saveAndFlush(org);
        jdbc.update("update users set status = 'DISABLED' where id = ?", admin.getId());
        read(org, viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.org.status").value("DISABLED"))
                .andExpect(jsonPath("$.members.length()").value(3))
                .andExpect(jsonPath("$.members[?(@.userId == '" + admin.getPublicId()
                        + "')].exclusionReason").value(contains("ACCOUNT_INACTIVE")))
                .andExpect(jsonPath("$.activeAdminCount").value(0))
                .andExpect(jsonPath("$.activeApproverCount").value(1))
                .andExpect(jsonPath("$.currentMailRecipientCount").value(0));
    }

    @Test
    void currentDesignatedRecipientsAndRequesterExclusionExplainLegacyFallback() throws Exception {
        jdbc.update("update user_org_roles set request_mail = true where user_id = ? and org_id = ?",
                manager.getId(), org.getId());
        read(org, viewer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legacyFallback").value(false))
                .andExpect(jsonPath("$.currentMailRecipientCount").value(1))
                .andExpect(jsonPath("$.members[?(@.userId == '" + manager.getPublicId()
                        + "')].selectionReason").value(contains("DESIGNATED")));
        mvc.perform(get(path(org)).param("requesterId", manager.getPublicId().toString())
                        .header("Authorization", bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.legacyFallback").value(true))
                .andExpect(jsonPath("$.currentMailRecipientCount").value(1))
                .andExpect(jsonPath("$.members[?(@.userId == '" + manager.getPublicId()
                        + "')].exclusionReason").value(contains("REQUESTER_EXCLUDED")))
                .andExpect(jsonPath("$.members[?(@.userId == '" + admin.getPublicId()
                        + "')].selectionReason").value(contains("LEGACY_ORG_ADMIN")));
    }

    @Test
    void emptyRosterIsVisibleWithoutAnInventedSystemRecipient() throws Exception {
        read(other, account(UserRole.SYS_VIEWER, "시스템 열람자"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(0))
                .andExpect(jsonPath("$.currentMailRecipientCount").value(0));
        mvc.perform(get("/api/v1/admin/orgs/" + SeedFixtures.UNKNOWN_ID + "/operations")
                        .header("Authorization", bearer(account(UserRole.SYS_ADMIN, "시스템 관리자"))))
                .andExpect(status().isNotFound());
    }

    private ResultActions read(Org target, User actor) throws Exception {
        return mvc.perform(get(path(target)).header("Authorization", bearer(actor)));
    }

    private static String path(Org target) {
        return "/api/v1/admin/orgs/" + target.getPublicId() + "/operations";
    }

    private String bearer(User actor) {
        return "Bearer " + jwtService.createAccessToken(actor);
    }

    private User account(UserRole role, String name) {
        User user = new User("org.ops." + UUID.randomUUID() + "@pusan.ac.kr", "{test-no-login}", name);
        user.setRole(role);
        user.setStatus(UserStatus.ACTIVE);
        user.setEmailVerifiedAt(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    private void grant(User user, Org target, UserRole role) {
        SeedFixtures.grantOrgRole(jdbc, user.getId(), target.getId(), role);
    }
}
