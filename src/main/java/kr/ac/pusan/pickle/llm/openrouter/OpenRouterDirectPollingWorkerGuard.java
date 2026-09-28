package kr.ac.pusan.pickle.llm.openrouter;

/** Fails closed if either configuration or the Spring context enables JobRunr. */
final class OpenRouterDirectPollingWorkerGuard {

    private OpenRouterDirectPollingWorkerGuard() {
    }

    static void requireWorkerDisabled(boolean configuredEnabled, boolean serverBeanPresent) {
        if (configuredEnabled || serverBeanPresent) {
            throw new IllegalStateException("OpenRouter direct polling requires the generic "
                    + "JobRunr worker to be disabled and absent");
        }
    }
}
