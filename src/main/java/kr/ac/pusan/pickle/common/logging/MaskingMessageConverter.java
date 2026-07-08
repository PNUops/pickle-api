package kr.ac.pusan.pickle.common.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Pattern;

/**
 * Logback conversion word {@code %maskedMsg}: masks values of secret-bearing
 * keys (password/token/secret/cipassword/…) in log messages so credentials and
 * tokens never land in logs raw (docs/plan/07 logging policy).
 *
 * <p>The dev/test-only {@code MockMailSender} logger is exempted so developers
 * can copy the emailed verification link from the console; that bean never
 * exists in prod profiles.</p>
 */
public class MaskingMessageConverter extends MessageConverter {

    public static final String MASK = "*****";

    private static final String EXEMPT_LOGGER = "kr.ac.pusan.pickle.mail.MockMailSender";

    /** key = value / key: value / "key":"value" / key=>value forms. */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(?i)([\"']?(?:initial[_-]?password|password|passwd|pwd|secret|token|cipassword"
                    + "|authorization|api[_-]?key|pickle_refresh)"
                    + "[a-z0-9_-]*[\"']?\\s*(?:=>|[:=])\\s*[\"']?)"
                    + "([^\\s,;&\"'}\\])]+)");

    /** Authorization: Bearer <jwt> and similar. */
    private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+");

    /**
     * Proxmox API token header: {@code PVEAPIToken=<tokenId>=<secret>}. Masked
     * as a whole (token id included) — must run before KEY_VALUE, which would
     * otherwise anchor on the embedded "Token=" and leave "PVEAPI" ambiguity.
     */
    private static final Pattern PVE_API_TOKEN = Pattern.compile("(?i)(PVEAPIToken=)[^\\s\"']+");

    @Override
    public String convert(ILoggingEvent event) {
        String message = super.convert(event);
        if (EXEMPT_LOGGER.equals(event.getLoggerName())) {
            return message;
        }
        return mask(message);
    }

    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        // Bearer first: "Authorization: Bearer <jwt>" would otherwise have only
        // the word "Bearer" swallowed by the key-value rule, leaving the JWT.
        String masked = BEARER.matcher(message).replaceAll("$1" + MASK);
        masked = PVE_API_TOKEN.matcher(masked).replaceAll("$1" + MASK);
        return KEY_VALUE.matcher(masked).replaceAll("$1" + MASK);
    }
}
