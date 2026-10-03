package kr.ac.pusan.pickle.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.junit.jupiter.api.Test;

class MockMailSenderFailureTest {
    @Test
    void aSyntheticRejectionIsDefiniteAndDoesNotStoreOrExposeTheMessage() {
        var sender = new MockMailSender("");
        RuntimeException failure = catchThrowableOfType(RuntimeException.class,
                () -> sender.send(MailMessage.text("person+fail@example.test",
                        "private-subject", "token=private-token")));

        assertThat(MailDeliveryFailure.classify(failure))
                .isEqualTo(new MailDeliveryFailure("SMTP_REJECTED", true));
        assertThat(failure.getMessage()).doesNotContain("person", "@", "private", "token=");
        assertThat(failure.getCause()).isNull();
        assertThat(sender.getMessages()).isEmpty();
    }
}
