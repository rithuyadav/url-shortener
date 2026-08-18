package com.example.urlshortener.exception;

import com.example.urlshortener.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * Centralized mapping from domain exceptions to HTTP responses, so controllers stay
 * focused on request/response shaping and every error path returns the same
 * {@link ErrorResponse} envelope. Each mapping is a deliberate status-code choice
 * (documented inline) rather than a blanket 400/500.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(UrlNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(UrlNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    @ExceptionHandler(UrlGoneException.class)
    public ResponseEntity<ErrorResponse> handleGone(UrlGoneException ex, HttpServletRequest req) {
        // 410 Gone rather than 404: the resource existed and its absence is permanent/expected,
        // which is meaningfully different for caching/monitoring/client behavior than "never existed".
        return build(HttpStatus.GONE, ex.getMessage(), req);
    }

    @ExceptionHandler(AliasAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleAliasConflict(AliasAlreadyExistsException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), req);
    }

    @ExceptionHandler(InvalidUrlException.class)
    public ResponseEntity<ErrorResponse> handleInvalidUrl(InvalidUrlException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    @ExceptionHandler(ShortCodeGenerationException.class)
    public ResponseEntity<ErrorResponse> handleGenerationFailure(ShortCodeGenerationException ex, HttpServletRequest req) {
        // 503: this signals a transient capacity/collision problem the caller can retry,
        // not a client input error and not an unexpected server bug.
        log.error("Short code generation exhausted retries: {}", ex.getMessage());
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), req);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException ex, HttpServletRequest req) {
        ErrorResponse body = new ErrorResponse(HttpStatus.TOO_MANY_REQUESTS.value(), "Too Many Requests",
                ex.getMessage(), req.getRequestURI());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message.isEmpty() ? "Validation failed" : message, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest req) {
        ErrorResponse body = new ErrorResponse(status.value(), status.getReasonPhrase(), message, req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
