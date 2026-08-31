package kr.ac.pusan.pickle.llm.openrouter;

/**
 * An OpenRouter management call that did not succeed. {@code status} is the
 * HTTP status, or 0 for a transport/protocol failure that never got one. The
 * message is generated locally and never carries a vendor response body,
 * credential or request body.
 */
public class OpenRouterException extends RuntimeException {

    private final int status;

    public OpenRouterException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
