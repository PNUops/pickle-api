package kr.ac.pusan.pickle.llm.openrouter;

/**
 * Server-derived freshness of a cached vendor observation.
 *
 * <p>Named for credits because that was its first use and the name is now in
 * the published contract; the model catalogue reuses it. The three states mean
 * the same thing in both, but <b>the threshold does not</b>: credits go STALE
 * after a fixed 30 minutes, the catalogue after three of its own refresh
 * intervals. Read the threshold from the service that computes it rather than
 * from this type.
 */
public enum OpenRouterCreditsFreshness {
    FRESH,
    STALE,
    UNKNOWN
}
