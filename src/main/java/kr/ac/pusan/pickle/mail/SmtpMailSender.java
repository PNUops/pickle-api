package kr.ac.pusan.pickle.mail;

import jakarta.mail.MessagingException;
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
 */
@Component
@Profile({"staging", "prod"})
public class SmtpMailSender implements MailSender {

    private final JavaMailSender javaMailSender;
    private final String from;

    public SmtpMailSender(JavaMailSender javaMailSender,
            @Value("${pickle.mail.from:${spring.mail.username:}}") String from) {
        this.javaMailSender = javaMailSender;
        this.from = from;
    }

    @Override
    public void send(MailMessage message) {
        MimeMessage mime = javaMailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mime,
                    MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name());
            // An unset from is legal for SimpleMailMessage but throws here, so
            // it stays unset and the session's own default applies.
            if (from != null && !from.isBlank()) {
                helper.setFrom(from);
            }
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
