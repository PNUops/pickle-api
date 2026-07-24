package kr.ac.pusan.pickle.mail;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Real SMTP sender for staging/prod. Connection settings come from
 * {@code spring.mail.*}, mapped from {@code PICKLE_SMTP_*} env vars in
 * application.yml. Config only — no real sends are exercised.
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
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.to());
        mail.setSubject(message.subject());
        mail.setText(message.body());
        javaMailSender.send(mail);
    }
}
