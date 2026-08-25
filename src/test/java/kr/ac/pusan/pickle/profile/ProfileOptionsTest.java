package kr.ac.pusan.pickle.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import kr.ac.pusan.pickle.user.UserPosition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The 직책·소속 catalogues. They are read before an account exists, so the
 * endpoint has to answer anonymously; and the console renders whatever it
 * sends, so the labels and the student-number rule have to arrive with it
 * rather than being re-derived on the far side.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class ProfileOptionsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DepartmentCatalog departments;

    @Autowired
    private ProfileOptionsService service;

    @Test
    void theCatalogueIsReadableWithoutASession() throws Exception {
        mockMvc.perform(get("/api/v1/meta/profile-options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions").isNotEmpty())
                .andExpect(jsonPath("$.departments").isNotEmpty());
    }

    @Test
    void everyPositionCarriesItsLabelAndStudentNumberRule() throws Exception {
        mockMvc.perform(get("/api/v1/meta/profile-options"))
                .andExpect(jsonPath("$.positions[?(@.code == 'STUDENT_UNDERGRAD')].label").value("학부생"))
                .andExpect(jsonPath("$.positions[?(@.code == 'STUDENT_UNDERGRAD')].requiresStudentNo")
                        .value(true))
                .andExpect(jsonPath("$.positions[?(@.code == 'PROFESSOR')].requiresStudentNo").value(false));
    }

    @Test
    void thePublishedRuleIsTheOneTheServerEnforces() {
        // The console gates its 학번 field on requiresStudentNo. If that ever
        // stopped agreeing with the enum the server validates against, the form
        // would either hide a required field or demand one nobody needs.
        service.positions().forEach(view ->
                assertThat(view.requiresStudentNo())
                        .isEqualTo(UserPosition.valueOf(view.code()).requiresStudentNo()));
    }

    @Test
    void theCatalogueCarriesItsFallbackAndRejectsUnknownCodes() {
        assertThat(departments.isKnown(DepartmentCatalog.OTHER)).isTrue();
        assertThat(departments.isKnown("NO_SUCH_DEPARTMENT")).isFalse();
        assertThat(departments.isKnown(null)).isFalse();
    }

    @Test
    void aStoredCodeResolvesToItsName() {
        assertThat(departments.nameOf("COMPUTER_SCIENCE")).isEqualTo("정보컴퓨터공학부");
        // An unknown code is what a department removed from the catalogue would
        // look like. Answering with the code beats answering with null.
        assertThat(departments.nameOf("RETIRED_DEPARTMENT")).isEqualTo("RETIRED_DEPARTMENT");
    }
}
