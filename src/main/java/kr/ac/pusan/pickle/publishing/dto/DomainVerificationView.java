package kr.ac.pusan.pickle.publishing.dto;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Contract schema {@code DomainVerification} — custom-domain ownership/propagation
 * guidance and polling state. Null for platform subdomains.
 */
public record DomainVerificationView(
        String token,
        List<RequiredRecord> requiredRecords,
        boolean aVerified,
        boolean txtVerified,
        @Nullable Instant lastCheckedAt,
        @Nullable String lastError) {

    /** A DNS record the user must create (A → proxy IP, TXT → ownership token). */
    public record RequiredRecord(String type, String name, String value) {
    }
}
