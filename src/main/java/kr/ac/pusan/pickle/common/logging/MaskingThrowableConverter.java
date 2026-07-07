package kr.ac.pusan.pickle.common.logging;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback conversion word {@code %maskedEx}: the standard {@code %ex} stack
 * trace rendering with the same secret masking as {@code %maskedMsg}.
 * Exception messages routinely embed request context (URLs with tokens, JDBC
 * messages with credentials), so throwable output must not bypass the
 * docs/plan/07 no-secrets-in-logs rule.
 */
public class MaskingThrowableConverter extends ThrowableProxyConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return MaskingMessageConverter.mask(super.convert(event));
    }
}
