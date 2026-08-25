package kr.ac.pusan.pickle.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import kr.ac.pusan.pickle.common.error.FieldValidationError;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the password policy. Two structural rules and one byte
 * ceiling are all that is left: the byte-boundary case pins the BCrypt 72-BYTE
 * limit surfacing as a 422 field error instead of an encoder exception (the
 * DTO {@code @Size} counts characters), and the pass cases pin what the policy
 * deliberately no longer rejects — a breach-corpus password, a platform word,
 * and a password containing the account's own address.
 */
class PasswordPolicyTest {

    private final PasswordPolicy policy = new PasswordPolicy();

    private String messageFor(String password) {
        List<FieldValidationError> errors = policy.check(password);
        assertThat(errors).hasSize(1);
        assertThat(errors.getFirst().field()).isEqualTo("password");
        return errors.getFirst().message();
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
    }

    @Test
    void strongPasswordsPass() {
        assertThat(policy.check("Corr3ct-horse-battery!")).isEmpty();
        assertThat(policy.check("purple-Monkey-dishwasher9")).isEmpty();
    }

    @Test
    void singleClassPassphrasesPassSinceTheCompositionRuleWasDropped() {
        // NIST SP 800-63B: no composition rules — a lowercase-only passphrase
        // is fine as long as it clears the structure checks.
        assertThat(policy.check("dogsandcats")).isEmpty();
        assertThat(policy.check("seoulwinter")).isEmpty();
    }

    @Test
    void listComparisonIsGoneSoBreachCorpusAndContextWordsNowPass() {
        // These four were the whole of the removed defence: two exact entries
        // of the old 47k blocklist, its stripped-form match, and a platform
        // context word. They are here so that reintroducing a list breaks a
        // test that says why it was dropped, rather than passing silently.
        assertThat(policy.check("1q2w3e4r5t")).isEmpty();
        assertThat(policy.check("qwerty1234")).isEmpty();
        assertThat(policy.check("Password-123!")).isEmpty();
        assertThat(policy.check("pusanuniv")).isEmpty();
    }

    @Test
    void aPasswordMayNowContainTheAccountAddress() {
        assertThat(policy.check("x!policy.tester!1")).isEmpty();
    }
}
