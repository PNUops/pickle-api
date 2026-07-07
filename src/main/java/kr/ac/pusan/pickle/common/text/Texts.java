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
}
