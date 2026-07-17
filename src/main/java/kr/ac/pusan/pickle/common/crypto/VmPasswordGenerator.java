package kr.ac.pusan.pickle.common.crypto;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Generates VM guest passwords (24-char CSPRNG, docs/plan/03 initial
 * credentials). Shared by the provisioning pipeline (step 5) and the password
 * reset endpoint so both mint identical-strength credentials.
 */
@Component
public class VmPasswordGenerator {

    private static final int PASSWORD_LENGTH = 24;
    private static final char[] PASSWORD_ALPHABET =
            ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#%^*-_+=")
                    .toCharArray();

    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder password = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            password.append(PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)]);
        }
        return password.toString();
    }
}
