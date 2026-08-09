package br.com.nora.api.application.analysis;

/**
 * Failures in the analysis pipeline. Sealed; each subtype carries a stable {@link #code()} mapped
 * by the GlobalExceptionHandler.
 */
public sealed class AnalysisException extends RuntimeException {

    private final String code;

    protected AnalysisException(String code, String message) {
        super(message);
        this.code = code;
    }

    protected AnalysisException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** The referenced meeting does not exist or does not belong to the tenant. */
    public static final class MeetingNotFound extends AnalysisException {
        public MeetingNotFound(java.util.UUID meetingId) {
            super("ANALYSIS_MEETING_NOT_FOUND", "meeting not found: " + meetingId);
        }
    }

    /** Transcript not found for the meeting (inconsistent state). */
    public static final class TranscriptMissing extends AnalysisException {
        public TranscriptMissing(java.util.UUID meetingId) {
            super("ANALYSIS_TRANSCRIPT_MISSING", "transcript missing for meeting: " + meetingId);
        }
    }

    /** Worker unavailable or returned an error. */
    public static final class WorkerUnavailable extends AnalysisException {
        public WorkerUnavailable(String detail, Throwable cause) {
            super("ANALYSIS_WORKER_UNAVAILABLE", "nlp worker unavailable: " + detail, cause);
        }
    }

    /** Worker responded but the payload did not validate against the expected schema. */
    public static final class InvalidWorkerResponse extends AnalysisException {
        public InvalidWorkerResponse(String detail, Throwable cause) {
            super("ANALYSIS_INVALID_RESPONSE", "invalid worker response: " + detail, cause);
        }
    }
}
