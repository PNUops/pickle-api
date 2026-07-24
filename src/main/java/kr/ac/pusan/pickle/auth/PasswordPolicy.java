package kr.ac.pusan.pickle.auth;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import kr.ac.pusan.pickle.common.error.ApiException;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.springframework.stereotype.Component;

/**
 * Server-side weak-password rejection (min 10 chars plus a zxcvbn-style
 * weakness check). Length is enforced by bean validation; this
 * component rejects structurally weak or well-known passwords with 422.
 */
@Component
public class PasswordPolicy {

    private static final Set<String> COMMON_PASSWORDS = Set.of(
            "password", "password1", "password12", "password123", "passw0rd", "p@ssword",
            "qwerty", "qwertyuiop", "qwerty1234", "qwer1234", "asdfghjkl", "zxcvbnm",
            "1234567890", "12345678910", "0123456789", "1q2w3e4r", "1q2w3e4r5t",
            "iloveyou", "letmein", "welcome", "monkey", "dragon", "sunshine", "princess",
            "football", "baseball", "superman", "batman", "trustno1", "admin", "administrator",
            "pusan", "pusanuniv", "busan", "pickle", "student");

    public void validate(String rawPassword, String email) {
        List<FieldValidationError> errors = check(rawPassword, email);
        if (!errors.isEmpty()) {
            throw ApiException.validationFailed(errors);
        }
    }

    List<FieldValidationError> check(String rawPassword, String email) {
        String lower = rawPassword.toLowerCase(Locale.ROOT);
        String stripped = lower.replaceAll("[^a-z0-9]", "");

        if (COMMON_PASSWORDS.contains(lower) || COMMON_PASSWORDS.contains(stripped)) {
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
