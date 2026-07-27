package kr.ac.pusan.pickle.auth;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Server-side weak-password rejection: a breach-derived blocklist (~47k
 * entries, embedded resource) plus structural checks (repetition, sequences,
 * e-mail-in-password) and platform context words. Length bounds are enforced
 * by bean validation on the DTOs; the BCrypt 72-BYTE ceiling is enforced here
 * because the DTO {@code @Size(max = 72)} counts characters — a 25-char Korean
 * password is 75 UTF-8 bytes and must come back as a clean 422, not a 500
 * from the encoder. Weak passwords answer 422 with a Korean field error.
 */
@Component
public class PasswordPolicy {

    /** BCrypt hashes at most 72 bytes; anything longer must be refused up front. */
    private static final int MAX_PASSWORD_BYTES = 72;

    /**
     * Platform context words (kept out of the embedded list on purpose —
     * they are ours, not the breach corpus'): exact-match against the
     * lowercased/stripped password like every blocklist entry.
     */
    private static final Set<String> CONTEXT_PASSWORDS = Set.of(
            "pusan", "pusanuniv", "busan", "pickle", "student", "ubuntu");

    private final Set<String> blocklist;

    public PasswordPolicy() {
        this.blocklist = loadBlocklist();
    }

    public void validate(String rawPassword, String email) {
        List<FieldValidationError> errors = check(rawPassword, email);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    List<FieldValidationError> check(String rawPassword, String email) {
        String lower = rawPassword.toLowerCase(Locale.ROOT);
        String stripped = lower.replaceAll("[^a-z0-9]", "");

        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            return error("비밀번호가 너무 깁니다. 한글 등 다국어 문자를 포함하면 더 짧게 입력해 주세요. (UTF-8 72바이트 이하)");
        }
        if (blocklist.contains(lower) || blocklist.contains(stripped)
                || CONTEXT_PASSWORDS.contains(lower) || CONTEXT_PASSWORDS.contains(stripped)) {
            return error("너무 흔한 비밀번호입니다. 다른 비밀번호를 사용해 주세요.");
        }
        if (characterClasses(rawPassword) < 2) {
            return error("영문 대문자·소문자·숫자·특수문자 중 두 종류 이상을 섞어 주세요.");
        }
        if (lower.chars().distinct().count() <= 2) {
            return error("같은 문자가 반복되는 비밀번호는 사용할 수 없습니다.");
        }
        if (isSequential(stripped)) {
            return error("연속된 문자·숫자로만 이루어진 비밀번호는 사용할 수 없습니다.");
        }
        String localPart = email.toLowerCase(Locale.ROOT).split("@", 2)[0];
        if (localPart.length() >= 4 && lower.contains(localPart)) {
            return error("이메일 주소가 포함된 비밀번호는 사용할 수 없습니다.");
        }
        return List.of();
    }

    /** One-time load of the embedded breach blocklist ('#' lines are comments). */
    private static Set<String> loadBlocklist() {
        Set<String> entries = new HashSet<>(65536);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("password-blocklist.txt").getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) != '#') {
                    entries.add(line);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("password-blocklist.txt is not readable", e);
        }
        return Set.copyOf(entries);
    }

    private static List<FieldValidationError> error(String message) {
        return List.of(new FieldValidationError("password", message));
    }

    private static int characterClasses(String password) {
        int classes = 0;
        if (password.chars().anyMatch(Character::isLowerCase)) {
            classes++;
        }
        if (password.chars().anyMatch(Character::isUpperCase)) {
            classes++;
        }
        if (password.chars().anyMatch(Character::isDigit)) {
            classes++;
        }
        if (password.chars().anyMatch(c -> !Character.isLetterOrDigit(c))) {
            classes++;
        }
        return classes;
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
