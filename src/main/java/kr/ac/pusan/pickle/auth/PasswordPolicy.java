package kr.ac.pusan.pickle.auth;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.springframework.stereotype.Component;

/**
 * Server-side weak-password rejection: structural checks only — repetition and
 * runs of sequential characters. Length bounds are enforced by bean validation
 * on the DTOs; the BCrypt 72-BYTE ceiling is enforced here because the DTO
 * {@code @Size(max = 72)} counts characters — a 25-char Korean password is 75
 * UTF-8 bytes and must come back as a clean 422, not a 500 from the encoder.
 * Weak passwords answer 422 with a Korean field error.
 *
 * <p>There is no list comparison here any more. A breach-derived blocklist
 * (~47k entries) and a short set of platform context words used to reject a
 * password by exact match against a stored list; both were dropped, together
 * with the e-mail-in-password check, because the friction they added to signup
 * outweighed what they caught. Do not reintroduce a list: an exception for
 * "just a few obvious words" is how the 47k list started, and five of the six
 * context words were already unreachable behind the 8-character floor.
 *
 * <p>No character-class rule either, and that predates this change: NIST
 * SP 800-63B forbids composition rules because users route around them
 * predictably.
 */
@Component
public class PasswordPolicy {

    /** BCrypt hashes at most 72 bytes; anything longer must be refused up front. */
    private static final int MAX_PASSWORD_BYTES = 72;

    public void validate(String rawPassword) {
        List<FieldValidationError> errors = check(rawPassword);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    List<FieldValidationError> check(String rawPassword) {
        String lower = rawPassword.toLowerCase(Locale.ROOT);
        String stripped = lower.replaceAll("[^a-z0-9]", "");

        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return error("비밀번호가 너무 깁니다. 한글 등 다국어 문자를 포함하면 더 짧게 입력해 주세요. (UTF-8 72바이트 이하)");
        }
        if (lower.chars().distinct().count() <= 2) {
            return error("같은 문자가 반복되는 비밀번호는 사용할 수 없습니다.");
        }
        if (isSequential(stripped)) {
            return error("연속된 문자·숫자로만 이루어진 비밀번호는 사용할 수 없습니다.");
        }
        return List.of();
    }

    private static List<FieldValidationError> error(String message) {
        return List.of(new FieldValidationError("password", message));
    }

    private static boolean isSequential(String stripped) {
        if (stripped.length() < 6) {
            return false;
        }
        boolean ascending = true;
        boolean descending = true;
        for (int i = 1; i < stripped.length(); i++) {
            if (stripped.charAt(i) != stripped.charAt(i - 1) + 1) {
                ascending = false;
            }
            if (stripped.charAt(i) != stripped.charAt(i - 1) - 1) {
                descending = false;
            }
        }
        return ascending || descending;
    }
}
