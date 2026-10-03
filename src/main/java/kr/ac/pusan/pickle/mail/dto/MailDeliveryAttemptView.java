package kr.ac.pusan.pickle.mail.dto;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record MailDeliveryAttemptView(int attemptNo, UUID dispatchId, Instant startedAt,
        @Nullable Instant completedAt, String outcome, @Nullable String failureCode) {
}
