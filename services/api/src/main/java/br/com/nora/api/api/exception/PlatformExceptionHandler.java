package br.com.nora.api.api.exception;

import br.com.nora.api.api.dto.ErrorResponse;
import br.com.nora.api.application.platform.PlatformConflictException;
import br.com.nora.api.application.platform.PlatformNotFoundException;
import br.com.nora.api.application.platform.PlatformUnavailableException;
import br.com.nora.api.application.platform.PlatformValidationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translator of control plane exceptions (ADR 0022/0023/0024). @Order(HIGHEST_PRECEDENCE) ensures
 * these specific handlers beat the catch-all Exception in GlobalExceptionHandler (which has the
 * default order). Reuses {@link ErrorResponse} to keep the error format consistent.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlatformExceptionHandler {

    @ExceptionHandler(PlatformUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleUnavailable(PlatformUnavailableException ex) {
        return body(HttpStatus.SERVICE_UNAVAILABLE, "PLATFORM_UNAVAILABLE", ex.getMessage());
    }

    @ExceptionHandler(PlatformNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(PlatformNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, "PLATFORM_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(PlatformConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(PlatformConflictException ex) {
        return body(HttpStatus.CONFLICT, "PLATFORM_CONFLICT", ex.getMessage());
    }

    @ExceptionHandler(PlatformValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(PlatformValidationException ex) {
        HttpStatus status =
                ex.isUnprocessable() ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.BAD_REQUEST;
        return body(status, "PLATFORM_VALIDATION", ex.getMessage());
    }

    private static ResponseEntity<ErrorResponse> body(
            HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                code,
                                message,
                                UUID.randomUUID().toString(),
                                Instant.now(),
                                List.of()));
    }
}
