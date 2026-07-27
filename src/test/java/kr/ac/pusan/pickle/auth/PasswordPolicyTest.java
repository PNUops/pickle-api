package kr.ac.pusan.pickle.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the password policy — first dedicated test of the
 * component (previously covered only through the signup flow). The blocklist
 * cases pin the embedded resource actually loading; the byte-boundary case
 * pins the BCrypt 72-BYTE ceiling surfacing as a 422 field error instead of
 * an encoder exception (the DTO {@code @Size} counts characters).
 */
class PasswordPolicyTest {

    private static final String EMAIL = "policy.tester@pusan.ac.kr";

    private final PasswordPolicy policy = new PasswordPolicy();

    private String messageFor(String password) {
        List<FieldValidationError> errors = policy.check(password, EMAIL);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().field()).isEqualTo("password");
        return errors.getFirst().message();
    }

    @Test
    void embeddedBlocklistRejectsBreachCorpusPasswords() {
        // Exact entries of the embedded list (top of the Pwdb corpus).
        assertThat(messageFor("1q2w3e4r5t")).contains("흔한 비밀번호");
        assertThat(messageFor("qwerty1234")).contains("흔한 비밀번호");
        // Stripped-form match: decorations around a blocked password don't help.
        assertThat(messageFor("Password-123!")).contains("흔한 비밀번호");
    }

    @Test
    void platformContextWordsStayBlocked() {
        assertThat(messageFor("pusanuniv")).contains("흔한 비밀번호");
    }

    @Test
    void bcryptByteCeilingAnswersAsFieldErrorNotEncoderFailure() {
        // 25 Korean chars = 75 UTF-8 bytes: passes the DTO's char-count max
        // but exceeds what BCrypt hashes — must be a clean policy error.
        assertThat(messageFor("가".repeat(25))).contains("72바이트");
    }

    @Test
    void structuralRulesStillApply() {
        assertThat(messageFor("xKxKxKxKxK")).contains("반복");
        assertThat(messageFor("Nopqrstuvw")).contains("연속");
        assertThat(messageFor("x!policy.tester!1")).contains("이메일");
    }

    @Test
    void strongPasswordsPass() {
        assertThat(policy.check("Corr3ct-horse-battery!", EMAIL)).isEmpty();
        assertThat(policy.check("purple-Monkey-dishwasher9", EMAIL)).isEmpty();
    }
}
