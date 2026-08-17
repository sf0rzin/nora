package br.com.nora.api.api.exception;

import br.com.nora.api.api.dto.ErrorResponse;
import br.com.nora.api.application.analysis.AnalysisException;
import br.com.nora.api.application.chat.ChatException;
import br.com.nora.api.application.iam.IamException;
import br.com.nora.api.application.iam.InvitationException;
import br.com.nora.api.application.identity.AuthException;
import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.stt.SttException;
import br.com.nora.api.application.task.TaskException;
import br.com.nora.api.application.tenant.TenantContextException;
import br.com.nora.api.application.tenant.TenantException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;

/** Central translator of exceptions into the standard error format. */
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
                    case "EMAIL_ALREADY_TAKEN", "ACCOUNT_TENANT_SHARED" -> HttpStatus.CONFLICT;
                    case "INVALID_CREDENTIALS", "EMAIL_NOT_VERIFIED", "REFRESH_TOKEN_INVALID" ->
                            HttpStatus.UNAUTHORIZED;
                    case "USER_DISABLED" -> HttpStatus.FORBIDDEN;
                    case "TOKEN_INVALID" -> HttpStatus.BAD_REQUEST;
                    case "RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
                    default -> HttpStatus.BAD_REQUEST;
                };
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            builder.header(HttpHeaders.RETRY_AFTER, "60");
        }
        return builder.body(
                new ErrorResponse(ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    @ExceptionHandler(MeetingException.class)
    public ResponseEntity<ErrorResponse> handleMeetingDomain(MeetingException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "MEETING_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "TRANSCRIPT_TOO_LARGE", "FILE_TOO_LARGE" -> HttpStatus.PAYLOAD_TOO_LARGE;
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
                    case "TENANT_CONTEXT_NOT_FOUND", "TENANT_CONTEXT_VERSION_NOT_FOUND" ->
                            HttpStatus.NOT_FOUND;
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

    @ExceptionHandler(br.com.nora.api.application.integration.IntegrationException.class)
    public ResponseEntity<ErrorResponse> handleIntegration(
            br.com.nora.api.application.integration.IntegrationException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "INTEGRATION_NOT_CONNECTED" -> HttpStatus.NOT_FOUND;
                    case "INTEGRATION_UNKNOWN_PROVIDER" -> HttpStatus.NOT_FOUND;
                    case "INTEGRATION_NOT_CONFIGURED" -> HttpStatus.UNPROCESSABLE_ENTITY;
                    case "INTEGRATION_INVALID_STATE" -> HttpStatus.BAD_REQUEST;
                    // Telegram pairing still missing the user's /start — state, not a 5xx failure.
                    case "INTEGRATION_PAIRING_PENDING" -> HttpStatus.CONFLICT;
                    case "INTEGRATION_PROVIDER_ERROR" -> HttpStatus.BAD_GATEWAY;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    @ExceptionHandler(br.com.nora.api.application.workflow.WorkflowException.class)
    public ResponseEntity<ErrorResponse> handleWorkflow(
            br.com.nora.api.application.workflow.WorkflowException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "WORKFLOW_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    case "WORKFLOW_INVALID_DEFINITION" -> HttpStatus.UNPROCESSABLE_ENTITY;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    @ExceptionHandler(ChatException.class)
    public ResponseEntity<ErrorResponse> handleChat(ChatException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "CHAT_SESSION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return ResponseEntity.status(status)
                .body(
                        new ErrorResponse(
                                ex.code(), ex.getMessage(), traceId(), Instant.now(), List.of()));
    }

    /**
     * Realtime STT session failures (ADR 0045). {@code STT_NOT_CONFIGURED} is a 503 rather than a
     * 500 because it is a deployment state, not a bug, and the desktop should tell the user to try
     * later instead of reporting a crash.
     */
    @ExceptionHandler(SttException.class)
    public ResponseEntity<ErrorResponse> handleStt(SttException ex) {
        HttpStatus status =
                switch (ex.code()) {
                    case "STT_RATE_LIMITED" -> HttpStatus.TOO_MANY_REQUESTS;
                    case "STT_NOT_CONFIGURED" -> HttpStatus.SERVICE_UNAVAILABLE;
                    case "STT_BROKER_ERROR" -> HttpStatus.BAD_GATEWAY;
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
                    case "IAM_GROUP_NOT_FOUND", "IAM_POLICY_NOT_FOUND", "IAM_USER_NOT_FOUND" ->
                            HttpStatus.NOT_FOUND;
                    case "IAM_NAME_TAKEN" -> HttpStatus.CONFLICT;
                    case "IAM_FORBIDDEN" -> HttpStatus.FORBIDDEN;
                    case "IAM_AUTHORIZATION_NOT_DECLARED" -> HttpStatus.FORBIDDEN;
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

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        // We do NOT leak ex.getMessage() — it may contain internal details. traceId in the log is
        // enough.
        String trace = traceId();
        LOG.warn("IllegalArgument traceId={} message={}", trace, ex.getMessage());
        return ResponseEntity.badRequest()
                .body(
                        new ErrorResponse(
                                "VALIDATION_FAILED",
                                "Invalid request.",
                                trace,
                                Instant.now(),
                                List.of()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(
                        new ErrorResponse(
                                "PAYLOAD_TOO_LARGE",
                                "Upload exceeds maximum allowed size.",
                                traceId(),
                                Instant.now(),
                                List.of()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest()
                .body(
                        new ErrorResponse(
                                "MALFORMED_REQUEST",
                                "Request body is malformed or missing.",
                                traceId(),
                                Instant.now(),
                                List.of()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(
                        new ErrorResponse(
                                "METHOD_NOT_ALLOWED",
                                "HTTP method not allowed for this endpoint.",
                                traceId(),
                                Instant.now(),
                                List.of()));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMedia(
            HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(
                        new ErrorResponse(
                                "UNSUPPORTED_MEDIA_TYPE",
                                "Unsupported media type.",
                                traceId(),
                                Instant.now(),
                                List.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        // Typical: invalid UUID in a @PathVariable.
        return ResponseEntity.badRequest()
                .body(
                        new ErrorResponse(
                                "VALIDATION_FAILED",
                                "Invalid parameter type.",
                                traceId(),
                                Instant.now(),
                                List.of(
                                        new ErrorResponse.FieldIssue(
                                                ex.getName(), "invalid value"))));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest()
                .body(
                        new ErrorResponse(
                                "VALIDATION_FAILED",
                                "Missing required parameter.",
                                traceId(),
                                Instant.now(),
                                List.of(
                                        new ErrorResponse.FieldIssue(
                                                ex.getParameterName(), "required"))));
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoHandlerFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(
                        new ErrorResponse(
                                "NOT_FOUND",
                                "Endpoint not found.",
                                traceId(),
                                Instant.now(),
                                List.of()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex) {
        String trace = traceId();
        LOG.warn("DataIntegrityViolation traceId={} root={}", trace, ex.getMostSpecificCause());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(
                        new ErrorResponse(
                                "CONFLICT",
                                "Resource conflict (duplicate or violates a constraint).",
                                trace,
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
        // Reuses the requestId set by the RequestIdFilter (the same id that shows up in the logs
        // and in the X-Request-Id header), making the error response's traceId correlatable.
        // Falls back to a UUID if the filter has not run (e.g.: error very early in the chain).
        String requestId = MDC.get("requestId");
        return requestId != null ? requestId : UUID.randomUUID().toString();
    }
}
