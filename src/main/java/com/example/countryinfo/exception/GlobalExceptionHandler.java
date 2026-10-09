package com.example.countryinfo.exception;

import com.example.countryinfo.config.CorrelationIdFilter;
import com.example.countryinfo.dto.ApiError;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

/**
 * Maps every exception to the single {@link ApiError} shape.
 * Clients never see stack traces, SQL or upstream payloads; full detail goes to the logs,
 * correlated by traceId.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final long retryAfterSeconds;

    public GlobalExceptionHandler(@Value("${app.errors.retry-after-seconds:30}") long retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    // ---- 400 -------------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .sorted()
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", req, details);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest req) {
        List<String> details = ex.getAllErrors().stream()
                .map(e -> e.getDefaultMessage() == null ? "invalid value" : e.getDefaultMessage())
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed", req, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is missing or is not valid JSON", req, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "Parameter '" + ex.getName() + "' has an invalid value", req, List.of());
    }

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<ApiError> handleInvalidRequest(InvalidRequestException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", ex.getMessage(), req, List.of());
    }

    // ---- 404 -------------------------------------------------------------------------

    @ExceptionHandler(CountryNotFoundException.class)
    ResponseEntity<ApiError> handleCountryNotFound(CountryNotFoundException ex, HttpServletRequest req) {
        log.info("country.not_found countryName={}", ex.getCountryName());
        return build(HttpStatus.NOT_FOUND, "COUNTRY_NOT_FOUND", ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> handleResourceNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", ex.getMessage(), req, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoRoute(NoResourceFoundException ex, HttpServletRequest req) {
        return build(HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND", "No endpoint at this path", req, List.of());
    }

    // ---- 405 / 406 / 415 -------------------------------------------------------------

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        ResponseEntity<ApiError> response = build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "Method " + ex.getMethod() + " is not supported here", req, List.of());
        if (ex.getSupportedHttpMethods() != null) {
            return ResponseEntity.status(response.getStatusCode())
                    .allow(ex.getSupportedHttpMethods().toArray(new HttpMethod[0]))
                    .body(response.getBody());
        }
        return response;
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Content-Type must be application/json", req, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiError> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest req) {
        // The body itself cannot be negotiated, so return the status only.
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    // ---- 409 -------------------------------------------------------------------------

    @ExceptionHandler(DuplicateCountryException.class)
    ResponseEntity<ApiError> handleDuplicate(DuplicateCountryException ex, HttpServletRequest req) {
        ApiError body = body(HttpStatus.CONFLICT, "COUNTRY_ALREADY_EXISTS", ex.getMessage(), req,
                List.of("existingId=" + ex.getExistingId(), "isoCode=" + ex.getIsoCode()));
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.LOCATION, "/api/v1/countries/" + ex.getExistingId())
                .body(body);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleOptimisticLock(ObjectOptimisticLockingFailureException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
                "The country was modified by another request; reload and retry", req, List.of());
    }

    // ---- 502 / 503 -------------------------------------------------------------------

    @ExceptionHandler(InvalidUpstreamResponseException.class)
    ResponseEntity<ApiError> handleBadUpstream(InvalidUpstreamResponseException ex, HttpServletRequest req) {
        log.error("upstream.invalid_response reason={}", ex.getMessage(), ex);
        return build(HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID_RESPONSE",
                "The country data provider returned an invalid response", req, List.of());
    }

    /**
     * Timeouts are deliberately mapped to 503 (not 504): from the client's point of view a
     * timed-out upstream, an unreachable upstream and an open circuit are the same situation
     * ("try again later"), so they share one status, one code family and a Retry-After header.
     */
    @ExceptionHandler(UpstreamUnavailableException.class)
    ResponseEntity<ApiError> handleUnavailable(UpstreamUnavailableException ex, HttpServletRequest req) {
        log.warn("upstream.unavailable cause={}", rootCauseName(ex));
        return unavailable("UPSTREAM_UNAVAILABLE", req);
    }

    @ExceptionHandler(CallNotPermittedException.class)
    ResponseEntity<ApiError> handleCircuitOpen(CallNotPermittedException ex, HttpServletRequest req) {
        log.warn("upstream.circuit_open message={}", ex.getMessage());
        return unavailable("UPSTREAM_CIRCUIT_OPEN", req);
    }

    @ExceptionHandler(BulkheadFullException.class)
    ResponseEntity<ApiError> handleBulkheadFull(BulkheadFullException ex, HttpServletRequest req) {
        log.warn("upstream.bulkhead_full message={}", ex.getMessage());
        return unavailable("UPSTREAM_BUSY", req);
    }

    // ---- 500 -------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("unhandled.exception path={}", req.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred. Quote the traceId when reporting it.", req, List.of());
    }

    // ---- helpers ---------------------------------------------------------------------

    private ResponseEntity<ApiError> unavailable(String code, HttpServletRequest req) {
        ApiError body = body(HttpStatus.SERVICE_UNAVAILABLE, code,
                "The country data provider is temporarily unavailable. Please retry shortly.", req, List.of());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(body);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message,
                                           HttpServletRequest req, List<String> details) {
        return ResponseEntity.status(status).body(body(status, code, message, req, details));
    }

    private ApiError body(HttpStatus status, String code, String message, HttpServletRequest req, List<String> details) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), code, message,
                req.getRequestURI(), CorrelationIdFilter.current(), details);
    }

    private static String rootCauseName(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }
}
