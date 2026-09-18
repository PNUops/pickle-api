package kr.ac.pusan.pickle.mail;

/**
 * Outbound mail port. {@link MockMailSender} (dev/test) logs and records
 * messages; {@link SmtpMailSender} (staging/prod) sends via SMTP; and
 * {@link DisabledMailSender} (isolated) refuses both paths.
 */
public interface MailSender {

    void send(MailMessage message);
}
