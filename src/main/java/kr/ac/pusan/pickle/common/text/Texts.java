package kr.ac.pusan.pickle.common.text;

import java.util.Locale;

/** Small text normalizers shared across services. */
public final class Texts {

    private Texts() {
    }

    /** Canonical account-email form (matches how signup stores emails). */
    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    /** Optional free-text fields: blank collapses to null, otherwise stripped. */
    public static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    /**
     * Sanitizes machine-reported text before it reaches audit rows, DB columns
     * or operator terminals: every ISO control character (ESC/ANSI sequences,
     * CR/LF included) is dropped and the result is truncated to
     * {@code maxLength}. Null stays null; a blank result collapses to null.
     */
    public static String sanitizeReported(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(Math.min(text.length(), maxLength));
        for (int i = 0; i < text.length() && sb.length() < maxLength; i++) {
            char c = text.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        String result = sb.toString().strip();
        return result.isEmpty() ? null : result;
    }
}
