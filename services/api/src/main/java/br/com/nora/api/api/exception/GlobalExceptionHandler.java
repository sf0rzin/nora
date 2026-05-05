package br.com.nora.api.api.exception;

import br.com.nora.api.api.dto.ErrorResponse;
import br.com.nora.api.application.identity.AuthException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Tradutor central de excecoes para o formato de erro padrao. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<ErrorResponse.FieldIssue> issues =
                ex.getBindingResult().getFieldErrors().stream()
                        .map(
                                fe ->
                                        new ErrorResponse.FieldIssue(
                                                fe.getField(), fe.getDefaultMessage()))
                        .toList();
        return ResponseEntity.badRequest()
                .body(
                        new ErrorResponse(
                                "VALIDATION_FAILED",
                                "Invalid request payload.",
                                traceId(),
                                Instant.now(),
                                issues));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuth(AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(
                        new ErrorResponse(
                                "UNAUTHENTICATED",
                                "Authentication required.",
                                traceId(),
                                Instant.now(),
                                List.of()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(
                        new ErrorResponse(
                                "FORBIDDEN",
                                "Access denied.",
                                traceId(),
                                Instant.now(),
                                List.of()));
    }

    @ExceptionHandler(AuthException.class)
    public ResponseEntity<ErrorResponse> handleAuthDomain(AuthException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "EMAIL_ALREADY_TAKEN" -> HttpStatus.CONFLICT;
                    case "INVALID_CREDENTIALS", "EMAIL_NOT_VERIFIED" -> HttpStatus.UNAUTHORIZED;
                    case "USER_DISABLED" -> HttpStatus.FORBIDDEN;
                    case "TOKEN_INVALID" -> HttpStatus.BAD_REQUEST;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(
                        new ErrorResponse(
                                "VALIDATION_FAILED",
                                ex.getMessage(),
                                traceId(),
                                Instant.now(),
                                List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception ex) {
        String trace = traceId();
        LOG.error("Unhandled exception traceId={}", trace, ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(
                        new ErrorResponse(
                                "INTERNAL_ERROR",
                                "Unexpected error.",
                                trace,
                                Instant.now(),
                                List.of()));
    }

    private static String traceId() {
        return UUID.randomUUID().toString();
    }
}
