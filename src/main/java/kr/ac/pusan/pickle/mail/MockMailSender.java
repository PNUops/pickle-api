package kr.ac.pusan.pickle.mail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/test mail sender: records messages in memory for tests and, when
 * {@code pickle.mail.mock-spool-path} is set (dev), appends the full body to a
 * service-user-only spool file. The journal gets recipient/subject only —
 * verification links carry bearer tokens, and the dev journal is readable by
 * anyone with log access on a publicly reachable host. An unprofiled launch
 * has no {@link MailSender} bean at all and fails fast at startup instead of
 * silently mocking mail.
 */
@Component
@Profile({"dev", "test"})
public class MockMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(MockMailSender.class);

    private final List<MailMessage> messages = new CopyOnWriteArrayList<>();
    private final Path spoolPath;

    public MockMailSender(@Value("${pickle.mail.mock-spool-path:}") String spoolPath) {
        this.spoolPath = spoolPath == null || spoolPath.isBlank() ? null : Path.of(spoolPath);
    }

    /** Test hook: recipients whose local part contains this tag fail to send
     *  (dispatcher backoff/FAILED paths need a deterministic SMTP failure). */
    static final String FAILING_RECIPIENT_TAG = "+fail";

    @Override
    public void send(MailMessage message) {
        String localPart = message.to().split("@", 2)[0];
        if (localPart.contains(FAILING_RECIPIENT_TAG)) {
            throw new IllegalStateException("모의 SMTP 실패 (수신자 " + message.to() + ")");
        }
        messages.add(message);
        log.info("[mock-mail] to={} subject={} (body withheld from journal{})",
                message.to(), message.subject(), spoolPath != null ? ", spooled" : "");
        appendToSpool(message);
    }

    private synchronized void appendToSpool(MailMessage message) {
        if (spoolPath == null) {
            return;
        }
        try {
            if (Files.notExists(spoolPath)) {
                Files.createFile(spoolPath,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            }
            String entry = "--- %s to=%s subject=%s%n%s%n".formatted(
                    Instant.now(), message.to(), message.subject(), message.body());
            Files.writeString(spoolPath, entry, StandardCharsets.UTF_8,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[mock-mail] spool write failed ({}): {}", spoolPath, e.getMessage());
        }
    }

    public List<MailMessage> getMessages() {
        return List.copyOf(messages);
    }

    public MailMessage lastMessageTo(String email) {
        return messages.reversed().stream()
                .filter(m -> m.to().equalsIgnoreCase(email))
                .findFirst()
                .orElse(null);
    }

    public void clear() {
        messages.clear();
    }
}
