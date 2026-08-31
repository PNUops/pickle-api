package kr.ac.pusan.pickle.llm.openrouter;

/** Why no account depletion forecast is shown. */
public enum OpenRouterForecastUnavailableReason {
    INSUFFICIENT_HISTORY,
    RESET_BOUNDARY,
    NO_CONSUMPTION,
    OUT_OF_RANGE
}
