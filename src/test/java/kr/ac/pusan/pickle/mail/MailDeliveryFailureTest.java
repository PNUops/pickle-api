package kr.ac.pusan.pickle.mail;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import java.net.SocketTimeoutException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

class MailDeliveryFailureTest {
    private static final String PRIVATE_RESPONSE = "private-recipient@example.test token=private-token body=private-body";

    @Test
    void explicitRecipientRejectionRemainsDefiniteInsideSpringFailureMap() throws Exception {
        var rejected = providerException("smtp.SMTPAddressFailedException",
                new Class<?>[] {InternetAddress.class, String.class, int.class, String.class},
                new InternetAddress("private-recipient@example.test"),
                "RCPT TO:<private-recipient@example.test>", 550, PRIVATE_RESPONSE);
        var nested = new MessagingException(PRIVATE_RESPONSE, rejected);
        var failed = new MailSendException(Map.of(new Object(), nested));

        assertThat(MailDeliveryFailure.classify(failed))
                .isEqualTo(new MailDeliveryFailure("SMTP_REJECTED", true));
        assertSafe(MailDeliveryFailure.classify(failed).exception());
    }

    @Test
    void aLostFinalDataResponseIsUnknownEvenWhenTheProviderCallsAddressesUnsent() throws Exception {
        var address = new InternetAddress("private-recipient@example.test");
        var failed = new SendFailedException(PRIVATE_RESPONSE,
                new SocketTimeoutException(PRIVATE_RESPONSE), null, new Address[] {address}, null);

        assertThat(MailDeliveryFailure.classify(new MailSendException(Map.of(new Object(), failed))))
                .isEqualTo(new MailDeliveryFailure("MAIL_DELIVERY_UNKNOWN", false));
    }

    @Test
    void anyAcceptedRecipientPreventsTreatingPartialFailureAsDefiniteNonHandoff() throws Exception {
        var address = new InternetAddress("private-recipient@example.test");
        var partial = smtpFailure(550, new Address[] {address});

        assertThat(MailDeliveryFailure.classify(new MailSendException(Map.of(new Object(), partial)))
                .definiteFailure()).isFalse();
    }

    @Test
    void unsuccessfulOrMissingSmtpResponseCodesAreNotRejectionProof() throws Exception {
        for (int code : new int[] {0, -1, 250, 354}) {
            var failed = smtpFailure(code, null);
            assertThat(MailDeliveryFailure.classify(new MailSendException(Map.of(new Object(), failed)))
                    .definiteFailure()).as("SMTP response %s", code).isFalse();
        }
    }

    @Test
    void explicitNegativeDataResponsesAreDefiniteRejections() throws Exception {
        for (int code : new int[] {451, 554}) {
            var failed = smtpFailure(code, null);
            assertThat(MailDeliveryFailure.classify(new MailSendException(Map.of(new Object(), failed))))
                    .as("SMTP response %s", code).isEqualTo(new MailDeliveryFailure("SMTP_REJECTED", true));
        }
    }

    @Test
    void connectionAuthenticationAndPreparationFailuresHaveSafeDefiniteCodes() throws Exception {
        Class<?> socketConnect = Class.forName("org.eclipse.angus.mail.util.SocketConnectException");
        Object socketFailure = socketConnect.getConstructor(String.class, Exception.class,
                String.class, int.class, int.class).newInstance(PRIVATE_RESPONSE,
                        new SocketTimeoutException(PRIVATE_RESPONSE), "private-host", 587, 1000);
        var connect = providerException("util.MailConnectException", new Class<?>[] {socketConnect}, socketFailure);
        assertThat(MailDeliveryFailure.classify(new MailSendException(Map.of(new Object(), connect))))
                .isEqualTo(new MailDeliveryFailure("MAIL_CONNECTION_FAILED", true));
        assertThat(MailDeliveryFailure.classify(new MailAuthenticationException(PRIVATE_RESPONSE)))
                .isEqualTo(new MailDeliveryFailure("MAIL_AUTHENTICATION_FAILED", true));
        assertThat(MailDeliveryFailure.classify(new MailPreparationException(PRIVATE_RESPONSE)))
                .isEqualTo(new MailDeliveryFailure("MAIL_PREPARATION_FAILED", true));
    }

    @Test
    void genericRuntimeFailureAndPostSendCloseFailureAreUnknown() {
        assertThat(MailDeliveryFailure.classify(new IllegalStateException(PRIVATE_RESPONSE)).definiteFailure())
                .isFalse();
        assertThat(MailDeliveryFailure.classify(new MailSendException(
                "Failed to close server connection after message sending",
                new MessagingException(PRIVATE_RESPONSE))).definiteFailure()).isFalse();
    }

    @Test
    void mixedBatchOutcomesDoNotClaimThatEveryMessageFailedBeforeHandoff() throws Exception {
        var reject = smtpFailure(451, null);
        var uncertain = new MessagingException(PRIVATE_RESPONSE, new SocketTimeoutException(PRIVATE_RESPONSE));
        assertThat(MailDeliveryFailure.classify(new MailSendException(
                Map.of(new Object(), reject, new Object(), uncertain))).definiteFailure()).isFalse();
    }

    @Test
    void syntheticAndDisabledSendersProvideDefiniteSafeFailureEvidence() {
        assertThat(MailDeliveryFailure.classify(MailDeliveryFailure.rejected()))
                .isEqualTo(new MailDeliveryFailure("SMTP_REJECTED", true));
        assertThat(MailDeliveryFailure.classify(MailDeliveryFailure.disabled()))
                .isEqualTo(new MailDeliveryFailure("MAIL_DELIVERY_DISABLED", true));
        assertSafe(MailDeliveryFailure.rejected());
        assertSafe(MailDeliveryFailure.disabled());
    }

    @Test
    void genericFailureCanBeWrappedWithoutCopyingProviderMessageOrCause() {
        RuntimeException safe = MailDeliveryFailure.classify(new IllegalStateException(PRIVATE_RESPONSE))
                .exception();
        assertThat(MailDeliveryFailure.classify(safe).definiteFailure()).isFalse();
        assertSafe(safe);
    }

    private static void assertSafe(RuntimeException failure) {
        assertThat(failure.getMessage()).doesNotContain("private-", "token=", "body=", "@", "RCPT", "MAIL FROM");
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
    }

    private static MessagingException smtpFailure(int returnCode, Address[] accepted) throws Exception {
        return providerException("smtp.SMTPSendFailedException", new Class<?>[] {String.class, int.class,
                String.class, Exception.class, Address[].class, Address[].class, Address[].class},
                ".", returnCode, PRIVATE_RESPONSE, null, accepted, null, null);
    }

    /** Angus is the installed runtime provider; these are its actual exception implementations. */
    private static MessagingException providerException(String suffix, Class<?>[] arguments, Object... values)
            throws Exception {
        return (MessagingException) Class.forName("org.eclipse.angus.mail." + suffix)
                .getConstructor(arguments).newInstance(values);
    }
}
