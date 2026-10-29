package kr.ac.pusan.pickle.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/** Acquires the real current preimage used by authorized termination tests. */
public final class TerminationFixtures {
    public static MockHttpServletRequestBuilder delete(MockMvc mvc, ObjectMapper json, String token, String path) throws Exception {
        String snapshot = mvc.perform(get(path + "/termination-impact").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String fingerprint = json.readTree(snapshot).get("fingerprint").asString();
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path)
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("confirmation", Map.of("fingerprint", fingerprint, "confirmed", true))));
    }
    private TerminationFixtures() { }
}
