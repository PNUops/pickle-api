package kr.ac.pusan.pickle.mail;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DisabledMailSenderTest {

    @Test
    void refusesDeliveryInsteadOfSendingOrSpooling() {
        MailMessage message = new MailMessage(
                "person@example.test", "subject", "body", null);

        assertThatThrownBy(() -> new DisabledMailSender().send(message))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("비활성화");
    }
}
