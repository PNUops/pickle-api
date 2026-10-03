package kr.ac.pusan.pickle.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Real SMTP sender for staging/prod. Connection settings come from
 * {@code spring.mail.*}, mapped from {@code PICKLE_SMTP_*} env vars in
 * application.yml. Config only — no real sends are exercised.
 *
 * <p>A mail carrying an HTML part goes out as multipart/alternative, so a
 * client that cannot render it still shows the text part.</p>
 *
 * <p>The From address is required and is checked when the bean is built, so a
 * missing or malformed value fails the context refresh instead of being
 * discovered one rejected message at a time. It is not derived from the SMTP
 * username: with a hosted sending service that username is a provider
 * credential, not an address.</p>
 */
@Component
@Profile({"staging", "prod"})
public class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMailSender;
    private final String from;

    public SmtpMailSender(JavaMailSender javaMailSender,
            @Value("${pickle.mail.from:}") String from) {
        this.javaMailSender = javaMailSender;
        this.from = requireAddress(from);
    }

    /**
     * The value has to be one address, optionally with a display name.
     * {@code InternetAddress.parse} with strict on still returns a bare local
     * part with no domain, which is the shape a sending service's username
     * has, so {@code validate} is what actually rejects it. (The
     * single-address constructor does throw on that input; this uses parse,
     * because one value may legitimately be two addresses and that has to be
     * counted rather than thrown.)
     *
     * <p>Nothing here carries the rejected value, the cause included: this
     * runs at startup, so it lands in the journal, and
     * {@code AddressException.getMessage} quotes the string it rejected --
     * which in the case this guard exists for is a credential. The log
     * masking is key=value shaped and does not catch a quoted bare token.</p>
     */
    private static String requireAddress(String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException(
                    "PICKLE_MAIL_FROM is required on the staging/prod profile");
        }
        try {
            InternetAddress[] parsed = InternetAddress.parse(from, true);
            if (parsed.length != 1) {
                throw new IllegalStateException("PICKLE_MAIL_FROM carries "
                        + parsed.length + " addresses; it takes exactly one");
            }
            parsed[0].validate();
        } catch (AddressException e) {
            // No cause: its message would quote the rejected value.
            throw new IllegalStateException("PICKLE_MAIL_FROM is not a mail "
                    + "address; it takes 'Name <local@domain>' or 'local@domain'");
        }
        return from;
    }

    @Override
    public void send(MailMessage message) {
        MimeMessage mime;
        try {
            mime = javaMailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mime,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name());
            // The string form, not the parsed one: the helper re-encodes the
            // display name in its own charset, which a pre-parsed address
            // would skip.
            helper.setFrom(from);
            helper.setTo(message.to());
            helper.setSubject(message.subject());
            if (message.hasHtml()) {
                helper.setText(message.textBody(), message.htmlBody());
            } else {
                helper.setText(message.textBody(), false);
            }
        } catch (MessagingException | RuntimeException e) {
            // Nothing has been handed to SMTP; do not retain an address or body in the cause.
            throw MailDeliveryFailure.preparationFailed();
        }
        try {
            javaMailSender.send(mime);
        } catch (RuntimeException e) {
            // Spring also throws on close after send, so a generic MailSendException
            // is not proof of non-delivery and must not initiate another send.
            throw MailDeliveryFailure.classify(e).exception();
        }
    }
}
