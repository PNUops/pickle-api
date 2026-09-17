package kr.ac.pusan.pickle.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.MailPreparationException;
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
     * The value has to be one address, optionally with a display name. Strict
     * parsing alone accepts a bare local part with no domain, which is the
     * shape a provider credential has, so {@code validate} is what actually
     * rejects it. The message names the shape of the value and never the value
     * itself, because it reaches the startup log.
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
            throw new IllegalStateException("PICKLE_MAIL_FROM is not a mail "
                    + "address; it takes 'Name <local@domain>' or 'local@domain'", e);
        }
        return from;
    }

    @Override
    public void send(MailMessage message) {
        MimeMessage mime = javaMailSender.createMimeMessage();
        try {
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
        } catch (MessagingException e) {
            // MailException so the notification dispatcher's retry path sees it
            // the same as a send failure.
            throw new MailPreparationException("메일 구성 실패", e);
        }
        javaMailSender.send(mime);
    }
}
