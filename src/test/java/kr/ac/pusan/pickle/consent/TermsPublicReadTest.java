package kr.ac.pusan.pickle.consent;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Public terms endpoints (no auth) return the seeded v1 documents. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class TermsPublicReadTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsCurrentVersionsWithoutAuth() throws Exception {
        mockMvc.perform(get("/api/v1/meta/terms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[?(@.docType=='TERMS_OF_SERVICE')].version").exists())
                .andExpect(jsonPath("$[?(@.docType=='PRIVACY_POLICY')].version").exists());
    }

    @Test
    void returnsMarkdownBodyForADocument() throws Exception {
        mockMvc.perform(get("/api/v1/meta/terms/TERMS_OF_SERVICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docType").value("TERMS_OF_SERVICE"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.body").isNotEmpty());
    }

    @Test
    void unknownDocTypeIsRejected() throws Exception {
        // A value outside the TermsDocType enum fails path conversion; the shared
        // handler renders it as a 422 validation problem.
        mockMvc.perform(get("/api/v1/meta/terms/NONSENSE"))
                .andExpect(status().isUnprocessableEntity());
    }
}
