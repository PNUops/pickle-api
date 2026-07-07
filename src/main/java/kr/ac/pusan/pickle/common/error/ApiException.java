package kr.ac.pusan.pickle.common.error;

import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Business exception rendered as an RFC 9457 problem response with the Pickle
 * {@code code} (and optional {@code errors[]}) extensions. Titles and details
 * are end-user facing and therefore Korean.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final String detail;
    private final List<FieldValidationError> errors;
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, null, null);
    }

    public ApiException(HttpStatus status, String code, String title, String detail,
            List<FieldValidationError> errors, Long retryAfterSeconds) {
        super(code + ": " + title);
        this.status = status;
        this.code = code;
        this.title = title;
        this.detail = detail;
        this.errors = errors;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public static ApiException validationFailed(List<FieldValidationError> errors) {
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.VALIDATION_FAILED,
                "입력값이 올바르지 않습니다", "요청 값을 확인해 주세요.", errors, null);
    }

    public static ApiException rateLimited(long retryAfterSeconds) {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, ErrorCodes.RATE_LIMITED,
                "요청이 너무 많습니다", "잠시 후 다시 시도해 주세요.", null, retryAfterSeconds);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getTitle() {
        return title;
    }

    public String getDetail() {
        return detail;
    }

    public List<FieldValidationError> getErrors() {
        return errors;
    }

    public Long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
