package kr.ac.pusan.pickle.mail;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Isolated validation profile: no external delivery and no local message spool. */
@Component
@Profile("isolated")
public class DisabledMailSender implements MailSender {

    @Override
    public void send(MailMessage message) {
        throw new IllegalStateException("격리 검증 환경에서는 메일 발송이 비활성화되어 있습니다.");
    }
}
