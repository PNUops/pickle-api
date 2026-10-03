package kr.ac.pusan.pickle.mail;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Isolated validation profile: no external delivery and no local message spool. */
@Component
@Profile("isolated")
public class DisabledMailSender implements MailSender {

    @Override
    public void send(MailMessage message) {
        throw MailDeliveryFailure.disabled();
    }
}
