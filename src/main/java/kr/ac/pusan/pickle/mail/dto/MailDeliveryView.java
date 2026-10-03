package kr.ac.pusan.pickle.mail.dto;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record MailDeliveryView(UUID id, String sourceKind, String event, String title,
        @Nullable UUID userId, @Nullable String recipientEmail, @Nullable String currentUserEmail,
        String status, String queueState, int attempts, @Nullable String failureCode,
        @Nullable Instant nextAttemptAt, @Nullable Instant sentAt, Instant createdAt,
        @Nullable UUID requestId, @Nullable UUID orgId, @Nullable UUID announcementId,
        @Nullable UUID notificationId, @Nullable String linkPath,
        @Nullable Long policyRevision, @Nullable String mailMode, boolean legacyAddressUnknown,
        boolean canResend, @Nullable String cannotResendReason, boolean processingUnconfirmed) {
}
