package kr.ac.pusan.pickle.llm;

/**
 * How a key's money limit renews on OpenRouter. Absent (null) means the limit
 * is a total cap that never resets — the default shape (operator decision
 * 2026-08-24), which also removes the KST/UTC skew question entirely: a cap
 * without a window has no boundary to disagree about. The windowed values map
 * to OpenRouter's {@code limit_reset}, whose windows reset at UTC midnight —
 * a fact the console states next to the selector.
 */
public enum CreditLimitReset {
    DAILY, WEEKLY, MONTHLY;

    /** The lowercase form OpenRouter's management API takes. */
    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
