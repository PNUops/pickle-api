package kr.ac.pusan.pickle.mail;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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

    @Test
    void refusalIsDefiniteWithoutCopyingTheMessage() {
        RuntimeException failure = catchThrowableOfType(RuntimeException.class,
                () -> new DisabledMailSender().send(MailMessage.text(
                        "private@example.test", "private-subject", "token=private-token")));
        assertThat(MailDeliveryFailure.classify(failure))
                .isEqualTo(new MailDeliveryFailure("MAIL_DELIVERY_DISABLED", true));
        assertThat(failure.getMessage()).doesNotContain("private", "token=", "@");
        assertThat(failure.getCause()).isNull();
    }
}
