package kr.ac.pusan.pickle.mail;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev/test mail sender: logs the full message (including the verification
 * link) at INFO and records messages in memory for tests. This logger is
 * exempted from log masking — the bean never exists in staging/prod.
 */
@Component
@Profile({"dev", "test", "default"})
public class MockMailSender implements MailSender {

    private static final Logger log = LoggerFactory.getLogger(MockMailSender.class);

    private final List<MailMessage> messages = new CopyOnWriteArrayList<>();

    @Override
    public void send(MailMessage message) {
        messages.add(message);
        log.info("[mock-mail] to={} subject={}\n{}", message.to(), message.subject(), message.body());
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
