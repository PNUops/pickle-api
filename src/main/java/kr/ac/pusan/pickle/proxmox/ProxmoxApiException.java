package kr.ac.pusan.pickle.proxmox;

/**
 * Proxmox API call failed: either an HTTP error response (the PVE
 * {@code message} field is preserved in {@link #apiMessage()}) or a transport
 * failure (connect/read timeout, connection reset — {@link #statusCode()} is
 * {@code 0} and the cause carries the I/O detail).
 *
 * <p>{@link #isTransient()} is the retry-classification contract for the
 * provisioning pipeline: 5xx / timeout / I/O count as
 * transient, 4xx as permanent. Note that PVE also answers 500 for some
 * logically-permanent errors ("config file already exists"), so retrying
 * callers must pair this with idempotent step guards — checking actual state
 * before re-running — rather than trusting the classification blindly.</p>
 */
public class ProxmoxApiException extends RuntimeException {

    /** HTTP status of the error response, or 0 when no response was received. */
    private final int statusCode;

    /** The PVE {@code message} response field; null for transport failures. */
    private final String apiMessage;

    public ProxmoxApiException(int statusCode, String apiMessage, String requestDescription) {
        super("Proxmox API error " + statusCode + " on " + requestDescription
                + (apiMessage != null && !apiMessage.isBlank() ? ": " + apiMessage.strip() : ""));
        this.statusCode = statusCode;
        this.apiMessage = apiMessage;
    }

    public ProxmoxApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.apiMessage = null;
    }

    public int statusCode() {
        return statusCode;
    }

    public String apiMessage() {
        return apiMessage;
    }

    /** True when a retry may help: transport failure/timeout or a 5xx response. */
    public boolean isTransient() {
        return statusCode == 0 || statusCode >= 500;
    }
}
