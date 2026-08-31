package kr.ac.pusan.pickle.llm.openrouter;

/** One error taxonomy shared by credential verification, credits and key polling. */
public final class OpenRouterErrorClassifier {

    private OpenRouterErrorClassifier() {
    }

    public static OpenRouterCredentialError classify(RuntimeException error) {
        if (!(error instanceof OpenRouterException vendor)) {
            return OpenRouterCredentialError.CREDENTIAL_ERROR;
        }
        if (vendor.status() == 401 || vendor.status() == 403) {
            return OpenRouterCredentialError.CREDENTIAL_ERROR;
        }
        if (vendor.status() == 429) {
            return OpenRouterCredentialError.THROTTLED;
        }
        if (vendor.status() == 0 || vendor.status() >= 500) {
            return OpenRouterCredentialError.VENDOR_UNAVAILABLE;
        }
        return OpenRouterCredentialError.VENDOR_REJECTED;
    }
}
