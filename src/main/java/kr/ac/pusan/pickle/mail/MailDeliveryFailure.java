package kr.ac.pusan.pickle.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

/** Safe delivery evidence; exception text and MIME message objects never become metadata. */
public record MailDeliveryFailure(String code, boolean definiteFailure) {
    private static final MailDeliveryFailure UNKNOWN = new MailDeliveryFailure("MAIL_DELIVERY_UNKNOWN", false);
    private static final MailDeliveryFailure REJECTED = new MailDeliveryFailure("SMTP_REJECTED", true);
    private static final MailDeliveryFailure PREPARATION = new MailDeliveryFailure("MAIL_PREPARATION_FAILED", true);
    private static final MailDeliveryFailure AUTHENTICATION = new MailDeliveryFailure("MAIL_AUTHENTICATION_FAILED", true);
    private static final MailDeliveryFailure CONNECTION = new MailDeliveryFailure("MAIL_CONNECTION_FAILED", true);
    private static final MailDeliveryFailure DISABLED = new MailDeliveryFailure("MAIL_DELIVERY_DISABLED", true);

    public MailDeliveryFailure {
        boolean known = switch (code) {
            case "SMTP_REJECTED", "MAIL_PREPARATION_FAILED", "MAIL_AUTHENTICATION_FAILED",
                    "MAIL_CONNECTION_FAILED", "MAIL_DELIVERY_DISABLED" -> true;
            case "MAIL_DELIVERY_UNKNOWN" -> false;
            default -> throw new IllegalArgumentException("Unsupported mail failure code");
        };
        if (known != definiteFailure) throw new IllegalArgumentException("Inconsistent mail failure evidence");
    }

    public static MailDeliveryFailure classify(RuntimeException failure) {
        return classifyCause(failure, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    public static RuntimeException rejected() {
        return REJECTED.exception();
    }

    public static RuntimeException mockRejected() {
        return new ClassifiedFailure(REJECTED, "모의 SMTP 실패");
    }

    public static RuntimeException disabled() {
        return DISABLED.exception();
    }

    public static RuntimeException preparationFailed() {
        return PREPARATION.exception();
    }

    /** Keeps a safe code only: retaining the original cause would expose provider response data. */
    public RuntimeException exception() {
        return new ClassifiedFailure(this);
    }

    private static MailDeliveryFailure classifyCause(Throwable failure, Set<Throwable> visited) {
        if (failure == null || !visited.add(failure)) return UNKNOWN;
        if (failure instanceof ClassifiedFailure safe) return safe.evidence;
        if (failure instanceof MailPreparationException || failure instanceof MailParseException
                || failure instanceof AddressException) return PREPARATION;
        if (failure instanceof MailAuthenticationException
                || failure instanceof jakarta.mail.AuthenticationFailedException) return AUTHENTICATION;
        if (isProviderType(failure, "org.eclipse.angus.mail.util.MailConnectException")) return CONNECTION;
        if (failure instanceof IOException) return UNKNOWN;
        if (failure instanceof MailSendException send) {
            // Spring can throw after a successful send while closing the connection.
            // Only per-message failures can establish non-handoff; never inspect MIME keys.
            if (send.getFailedMessages().isEmpty()) return UNKNOWN;
            MailDeliveryFailure result = null;
            for (Exception messageFailure : send.getFailedMessages().values()) {
                Set<Throwable> branch = Collections.newSetFromMap(new IdentityHashMap<>());
                branch.addAll(visited);
                MailDeliveryFailure classified = classifyCause(messageFailure, branch);
                if (!classified.definiteFailure()) return UNKNOWN;
                result = result == null ? classified : result;
            }
            return result == null ? UNKNOWN : result;
        }
        if (failure instanceof SendFailedException send && send.getValidSentAddresses() != null
                && send.getValidSentAddresses().length > 0) return UNKNOWN;
        if (isProviderType(failure, "org.eclipse.angus.mail.smtp.SMTPSendFailedException")
                || isProviderType(failure, "org.eclipse.angus.mail.smtp.SMTPAddressFailedException")
                || isProviderType(failure, "org.eclipse.angus.mail.smtp.SMTPSenderFailedException")) {
            return smtpNegative(failure);
        }
        if (failure instanceof MessagingException messaging) {
            MailDeliveryFailure next = classifyCause(messaging.getNextException(), visited);
            if (next.definiteFailure()) return next;
        }
        return classifyCause(failure.getCause(), visited);
    }

    private static boolean isProviderType(Throwable failure, String typeName) {
        for (Class<?> type = failure.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals(typeName)) return true;
        }
        return false;
    }

    private static MailDeliveryFailure smtpNegative(Throwable failure) {
        // The starter supplies Angus at runtime. Read its documented numeric response,
        // without adding a compile dependency or inspecting provider messages/addresses.
        try {
            int returnCode = ((Number) failure.getClass().getMethod("getReturnCode").invoke(failure)).intValue();
            return returnCode >= 400 && returnCode < 600 ? REJECTED : UNKNOWN;
        } catch (ReflectiveOperationException | ClassCastException | SecurityException ignored) {
            return UNKNOWN;
        }
    }

    private static final class ClassifiedFailure extends IllegalStateException {
        private final MailDeliveryFailure evidence;

        private ClassifiedFailure(MailDeliveryFailure evidence) {
            this(evidence, switch (evidence.code()) {
                case "MAIL_DELIVERY_DISABLED" -> "격리 검증 환경에서는 메일 발송이 비활성화되어 있습니다.";
                case "SMTP_REJECTED" -> "메일 서버가 발송을 거절했습니다.";
                case "MAIL_PREPARATION_FAILED" -> "메일 구성을 완료하지 못했습니다.";
                case "MAIL_AUTHENTICATION_FAILED" -> "메일 서버 인증을 완료하지 못했습니다.";
                case "MAIL_CONNECTION_FAILED" -> "메일 서버에 연결하지 못했습니다.";
                default -> "메일 서버 인계 결과를 확인하지 못했습니다.";
            });
        }

        private ClassifiedFailure(MailDeliveryFailure evidence, String safeMessage) {
            super(safeMessage);
            this.evidence = evidence;
        }
    }
}
