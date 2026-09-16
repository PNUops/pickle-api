package kr.ac.pusan.pickle.mail;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * The real sender is {@code @Profile("staging","prod")}, so no integration test
 * ever constructs it: these assertions are the only thing standing between a
 * mistake here and production mail. They cover the shape of what goes on the
 * wire — multipart when there is HTML, a single text part when there is not,
 * UTF-8 throughout — without opening a connection.
 */
class SmtpMailSenderTest {

    /**
     * Captures the MimeMessage instead of connecting to an SMTP server.
     * {@code saveChanges} is what a real send does before writing the message
     * out, and it is what settles the headers being asserted below.
     */
    private static final class CapturingMailSender extends JavaMailSenderImpl {
        private final List<MimeMessage> sent = new ArrayList<>();

        @Override
        public void send(MimeMessage... messages) {
            try {
                for (MimeMessage message : messages) {
                    message.saveChanges();
                }
            } catch (jakarta.mail.MessagingException e) {
                throw new IllegalStateException(e);
            }
            sent.addAll(List.of(messages));
        }
    }

    private final CapturingMailSender javaMailSender = new CapturingMailSender();

    @Test
    void mailWithHtmlGoesOutAsMultipartCarryingBothParts() throws Exception {
        new SmtpMailSender(javaMailSender, "pickle@pusan.ac.kr")
                .send(new MailMessage("a@pusan.ac.kr", "[Pickle] 사용 종료 안내",
                        "평문 본문", "<html><body>본문</body></html>"));

        MimeMessage sent = javaMailSender.sent.getFirst();
        assertThat(sent.getContent()).isInstanceOf(MimeMultipart.class);
        assertThat(partTypes(sent))
                .anyMatch(type -> type.startsWith("text/plain"))
                .anyMatch(type -> type.startsWith("text/html"));
        assertThat(partTypes(sent)).allMatch(type -> type.toUpperCase().contains("UTF-8"));
        // a Korean subject has to survive as RFC 2047, not as raw bytes
        assertThat(sent.getSubject()).isEqualTo("[Pickle] 사용 종료 안내");
        assertThat(sent.getFrom()[0].toString()).isEqualTo("pickle@pusan.ac.kr");
        assertThat(sent.getAllRecipients()[0].toString()).isEqualTo("a@pusan.ac.kr");
    }

    /**
     * Which body landed in which part, and in which order. Asserting the two
     * content types alone cannot see a swapped {@code setText(html, text)}:
     * both parts still exist, both types still match, and every reader gets
     * markup as their plain text. The order matters too — in
     * multipart/alternative a client shows the last part it understands, so
     * the plain part has to come first for the HTML to win.
     */
    /**
     * Which body landed in which part, read the way a mail client reads it.
     * Asserting the two content types alone cannot see a swapped
     * {@code setText(html, text)}: both parts still exist, both types still
     * match, and every reader gets markup as their plain text. The order
     * matters too — in multipart/alternative a client shows the last part it
     * understands, so the plain part has to come first for the HTML to win.
     */
    @Test
    void thePlainPartCarriesTheTextAndComesBeforeTheHtml() throws Exception {
        new SmtpMailSender(javaMailSender, "pickle@pusan.ac.kr")
                .send(new MailMessage("a@pusan.ac.kr", "제목",
                        "평문 본문", "<html><body>본문</body></html>"));

        List<Part> parts = parts(reparse(javaMailSender.sent.getFirst()));
        assertThat(parts).hasSize(2);
        assertThat(parts.getFirst().type()).startsWith("text/plain");
        assertThat(parts.getFirst().body()).isEqualTo("평문 본문");
        assertThat(parts.get(1).type()).startsWith("text/html");
        assertThat(parts.get(1).body()).isEqualTo("<html><body>본문</body></html>");
    }

    @Test
    void mailWithoutHtmlKeepsASingleTextPart() throws Exception {
        new SmtpMailSender(javaMailSender, "pickle@pusan.ac.kr")
                .send(MailMessage.text("a@pusan.ac.kr", "제목", "평문만"));

        assertThat(partTypes(javaMailSender.sent.getFirst()))
                .singleElement().asString().startsWith("text/plain");
    }

    @Test
    void blankFromIsLeftUnsetRatherThanRejected() {
        // SimpleMailMessage tolerated a blank from; MimeMessageHelper throws on
        // one, and this sender is only ever built from configuration that may
        // not carry the value.
        new SmtpMailSender(javaMailSender, "  ")
                .send(MailMessage.text("a@pusan.ac.kr", "제목", "본문"));

        assertThat(javaMailSender.sent).hasSize(1);
    }

    /** One leaf part: its content type and the body that came with it. */
    private record Part(String type, Object body) {
    }

    /**
     * Serializes the message and reads it back. On the in-memory message
     * {@code getContent()} hands back the two alternative bodies in the
     * opposite order to their own {@code Content-Type} headers — the bytes on
     * the wire are correct, the accessor is not — so a test that means to
     * check which body is in which part has to parse the bytes, exactly as a
     * receiving client does.
     */
    private static MimeMessage reparse(MimeMessage message) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        message.writeTo(bytes);
        return new MimeMessage(message.getSession(),
                new java.io.ByteArrayInputStream(bytes.toByteArray()));
    }

    /** Every leaf part in wire order, type and body captured in one walk. */
    private static List<Part> parts(MimeMessage message) throws Exception {
        List<Part> parts = new ArrayList<>();
        collectParts(message.getContent(), message.getContentType(), parts);
        return parts;
    }

    private static void collectParts(Object content, String contentType, List<Part> parts)
            throws Exception {
        if (content instanceof MimeMultipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                var part = multipart.getBodyPart(i);
                collectParts(part.getContent(), part.getContentType(), parts);
            }
        } else {
            parts.add(new Part(contentType, content));
        }
    }

    /** Content types of every leaf part, flattening nested multiparts. */
    private static List<String> partTypes(MimeMessage message) throws Exception {
        List<String> types = new ArrayList<>();
        collect(message.getContent(), message.getContentType(), types);
        return types;
    }

    private static void collect(Object content, String contentType, List<String> types)
            throws Exception {
        if (content instanceof MimeMultipart multipart) {
            for (int i = 0; i < multipart.getCount(); i++) {
                var part = multipart.getBodyPart(i);
                collect(part.getContent(), part.getContentType(), types);
            }
        } else {
            types.add(contentType);
        }
    }
}
