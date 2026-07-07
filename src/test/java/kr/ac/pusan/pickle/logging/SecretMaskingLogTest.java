package kr.ac.pusan.pickle.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import kr.ac.pusan.pickle.common.logging.MaskingMessageConverter;
import kr.ac.pusan.pickle.support.EmbeddedPostgresConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * "No secrets in logs" gate (docs/plan/07): captures root-logger events with a
 * ListAppender, renders them through the same %maskedMsg pattern configured in
 * logback-spring.xml, and asserts passwords/tokens never appear raw.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmbeddedPostgresConfig.class)
class SecretMaskingLogTest {

    private static final String PASSWORD = "Sup3r-secret-value!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private LoggerContext loggerContext;
    private Logger rootLogger;
    private ListAppender<ILoggingEvent> listAppender;
    private PatternLayout maskedLayout;

    @BeforeEach
    void attachCapture() {
        loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);

        listAppender = new ListAppender<>();
        listAppender.setContext(loggerContext);
        listAppender.start();
        rootLogger.addAppender(listAppender);

        // Same conversion word wiring as logback-spring.xml (conversionRule
        // registers %maskedMsg / %maskedEx on the shared LoggerContext).
        maskedLayout = new PatternLayout();
        maskedLayout.setContext(loggerContext);
        maskedLayout.setPattern("%logger %maskedMsg%n%maskedEx");
        maskedLayout.start();
    }

    @AfterEach
    void detachCapture() {
        rootLogger.detachAppender(listAppender);
        listAppender.stop();
        maskedLayout.stop();
    }

    @Test
    void authFlowLogsNeverContainRawSecrets() throws Exception {
        String email = "mask.probe@pusan.ac.kr";
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "password", PASSWORD, "name", "마스킹"))))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "password", PASSWORD))))
                .andExpect(status().isForbidden());

        // Deliberately leak-shaped log lines: the pattern must mask them all.
        var probe = LoggerFactory.getLogger("masking.probe");
        probe.info("login attempt password={} token={}", PASSWORD, "tok-0123456789abcdef");
        probe.info("payload {\"password\": \"{}\", \"refreshToken\": \"{}\"}", PASSWORD, "rt-fedcba9876543210");
        probe.info("cloud-init cipassword=vm-secret-pw! secret: hunter2-hunter2");
        probe.info("header Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig");

        List<String> rendered = listAppender.list.stream().map(maskedLayout::doLayout).toList();
        String allOutput = String.join("", rendered);

        assertThat(allOutput).isNotEmpty();
        assertThat(allOutput)
                .doesNotContain(PASSWORD)
                .doesNotContain("tok-0123456789abcdef")
                .doesNotContain("rt-fedcba9876543210")
                .doesNotContain("vm-secret-pw!")
                .doesNotContain("hunter2-hunter2")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9.payload.sig");

        String probeOutput = rendered.stream()
                .filter(line -> line.startsWith("masking.probe"))
                .reduce("", String::concat);
        assertThat(probeOutput)
                .contains("password=" + MaskingMessageConverter.MASK)
                .contains("token=" + MaskingMessageConverter.MASK)
                .contains("cipassword=" + MaskingMessageConverter.MASK);
    }

    @Test
    void stackTracesAreMaskedToo() {
        // Exception messages routinely carry request context; %maskedEx must
        // scrub them exactly like %maskedMsg scrubs regular messages.
        var probe = LoggerFactory.getLogger("masking.probe.ex");
        var cause = new IllegalStateException("upstream rejected token=tok-exc-1234567890");
        probe.error("proxmox call failed",
                new RuntimeException("clone failed for cipassword=vm-exc-secret! at node pve1", cause));

        String rendered = listAppender.list.stream().map(maskedLayout::doLayout)
                .reduce("", String::concat);

        assertThat(rendered)
                .contains("proxmox call failed")
                .contains("RuntimeException")
                .contains("Caused by")
                .doesNotContain("vm-exc-secret!")
                .doesNotContain("tok-exc-1234567890")
                .contains("cipassword=" + MaskingMessageConverter.MASK)
                .contains("token=" + MaskingMessageConverter.MASK);
    }
}
