package kr.ac.pusan.pickle.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Renders every error as an RFC 9457 problem (application/problem+json) with
 * the Pickle extensions {@code code} and {@code errors[]} (contract v0.2.0).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(ex.getStatus(), ex.getTitle(), ex.getDetail(), ex.getCode(), request);
        if (ex.getErrors() != null && !ex.getErrors().isEmpty()) {
            problem.setProperty("errors", ex.getErrors());
        }
        HttpHeaders headers = new HttpHeaders();
        if (ex.getRetryAfterSeconds() != null) {
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()));
        }
        return ResponseEntity.status(ex.getStatus()).headers(headers).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleBeanValidation(MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        List<FieldValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldValidationError(fe.getField(),
                        fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "올바르지 않은 값입니다."))
                .toList();
        return validationProblem(errors, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException ex,
            HttpServletRequest request) {
        List<FieldValidationError> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldValidationError(
                                result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage() != null ? error.getDefaultMessage() : "올바르지 않은 값입니다.")))
                .toList();
        return validationProblem(errors, request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> handleUnreadable(Exception ex, HttpServletRequest request) {
        // A chunked body that blew through a server-side byte cap aborts
        // mid-read inside message conversion; it must answer the same 413 as
        // a declared Content-Length violation, not a generic 422.
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof kr.ac.pusan.pickle.common.web.RequestBodyCapExceededException) {
                ProblemDetail problem = problem(HttpStatus.PAYLOAD_TOO_LARGE,
                        "요청 본문이 너무 큽니다", "요청 본문이 허용 크기를 초과했습니다.",
                        ErrorCodes.VALIDATION_FAILED, request);
                return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(problem);
            }
        }
        return validationProblem(List.of(), request);
    }

    /**
     * Method-security denials ({@code @PreAuthorize}) surface here instead of
     * the filter-chain {@code AccessDeniedHandler}; render the same problem
     * (contract: components/responses/Forbidden).
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.FORBIDDEN, "접근 권한이 없습니다",
                "이 작업을 수행할 권한이 없습니다.", ErrorCodes.ACCESS_DENIED, request);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(NoResourceFoundException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "리소스를 찾을 수 없습니다",
                "요청한 리소스가 존재하지 않습니다.", ErrorCodes.RESOURCE_NOT_FOUND, request);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.METHOD_NOT_ALLOWED, "허용되지 않은 메서드입니다",
                "이 경로에서 지원하지 않는 HTTP 메서드입니다.", ErrorCodes.METHOD_NOT_ALLOWED, request);
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다",
                "잠시 후 다시 시도해 주세요.", ErrorCodes.INTERNAL_ERROR, request);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    private ResponseEntity<ProblemDetail> validationProblem(List<FieldValidationError> errors,
            HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "입력값이 올바르지 않습니다",
                "요청 값을 확인해 주세요.", ErrorCodes.VALIDATION_FAILED, request);
        if (!errors.isEmpty()) {
            problem.setProperty("errors", errors);
        }
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(problem);
    }

    private ProblemDetail problem(HttpStatus status, String title, String detail, String code,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        return problem;
    }
}
