package br.com.nora.api.api.exception;

import br.com.nora.api.api.dto.ErrorResponse;
import br.com.nora.api.application.analysis.AnalysisException;
import br.com.nora.api.application.iam.IamException;
import br.com.nora.api.application.iam.InvitationException;
import br.com.nora.api.application.identity.AuthException;
import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.speech.SpeechException;
import br.com.nora.api.application.task.TaskException;
import br.com.nora.api.application.tenant.TenantContextException;
import br.com.nora.api.application.tenant.TenantException;
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
                    case "INVALID_CREDENTIALS", "EMAIL_NOT_VERIFIED", "REFRESH_TOKEN_INVALID" ->
                            HttpStatus.UNAUTHORIZED;
                    case "USER_DISABLED" -> HttpStatus.FORBIDDEN;
                    case "TOKEN_INVALID" -> HttpStatus.BAD_REQUEST;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    @ExceptionHandler(MeetingException.class)
    public ResponseEntity<ErrorResponse> handleMeetingDomain(MeetingException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "MEETING_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "TRANSCRIPT_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    @ExceptionHandler(AnalysisException.class)
    public ResponseEntity<ErrorResponse> handleAnalysisDomain(AnalysisException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "ANALYSIS_MEETING_NOT_FOUND", "ANALYSIS_TRANSCRIPT_MISSING" ->
                            HttpStatus.NOT_FOUND;
                    case "ANALYSIS_WORKER_UNAVAILABLE" -> HttpStatus.BAD_GATEWAY;
                    case "ANALYSIS_INVALID_RESPONSE" -> HttpStatus.BAD_GATEWAY;
                    default -> HttpStatus.INTERNAL_SERVER_ERROR;
                };
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    @ExceptionHandler(TenantContextException.class)
    public ResponseEntity<ErrorResponse> handleTenantContext(TenantContextException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "TENANT_CONTEXT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    @ExceptionHandler(TenantException.class)
    public ResponseEntity<ErrorResponse> handleTenant(TenantException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "TENANT_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "TENANT_DOMAIN_INVALID" -> HttpStatus.UNPROCESSABLE_ENTITY;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    @ExceptionHandler(TaskException.class)
    public ResponseEntity<ErrorResponse> handleTask(TaskException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "TASK_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    @ExceptionHandler(IamException.class)
    public ResponseEntity<ErrorResponse> handleIam(IamException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "IAM_GROUP_NOT_FOUND", "IAM_POLICY_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "IAM_NAME_TAKEN" -> HttpStatus.CONFLICT;
                    case "IAM_FORBIDDEN" -> HttpStatus.FORBIDDEN;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    @ExceptionHandler(InvitationException.class)
    public ResponseEntity<ErrorResponse> handleInvitation(InvitationException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "EMAIL_DOMAIN_NOT_ALLOWED" -> HttpStatus.UNPROCESSABLE_ENTITY;
                    case "INVITE_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "INVITE_ALREADY_ACCEPTED", "INVITE_DUPLICATE_PENDING" ->
                            HttpStatus.CONFLICT;
                    case "INVITE_EXPIRED" -> HttpStatus.GONE;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    @ExceptionHandler(SpeechException.class)
    public ResponseEntity<ErrorResponse> handleSpeechDomain(SpeechException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "RATE_LIMIT_EXCEEDED" -> HttpStatus.TOO_MANY_REQUESTS;
                    case "INVALID_REGION" -> HttpStatus.BAD_REQUEST;
                    case "BROKER_ERROR" -> HttpStatus.BAD_GATEWAY;
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
