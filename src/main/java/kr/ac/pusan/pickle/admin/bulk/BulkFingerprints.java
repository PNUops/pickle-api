package kr.ac.pusan.pickle.admin.bulk;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/**
 * The fingerprint a preview hands out and an apply hands back: a short hash
 * of every value the change kind could touch on one target, so the apply can
 * tell that the target it is about to write is the one the administrator
 * looked at.
 *
 * <p>Values are reduced to plain JSON scalars first, so a date or an amount
 * hashes the same whether it came off an entity or a snapshot. Sixteen hex
 * characters are plenty: this guards against a target changing between two
 * screens, not against an adversary.
 */
final class BulkFingerprints {

    private BulkFingerprints() {
    }

    static String of(ObjectMapper objectMapper, Map<String, Object> values) {
        byte[] canonical = objectMapper.writeValueAsBytes(values);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /**
     * One JSON-stable spelling per value type. Kept beside the hash so the
     * diff a preview shows and the fingerprint it computes agree on what a
     * value looks like.
     */
    static @Nullable Object plain(@Nullable Object value) {
        return switch (value) {
            case null -> null;
            case BigDecimal amount -> amount.stripTrailingZeros().toPlainString();
            case Instant instant -> instant.toString();
            case LocalDate date -> date.toString();
            case UUID id -> id.toString();
            case Enum<?> constant -> constant.name();
            case List<?> list -> list.stream().map(BulkFingerprints::plain).toList();
            default -> value;
        };
    }

    /** {@code new BigDecimal("1.00")} and {@code "1"} are one amount for the hash's purposes. */
    static @Nullable Object plainAmount(@Nullable BigDecimal amount) {
        return amount == null ? null : amount.stripTrailingZeros().toPlainString();
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
