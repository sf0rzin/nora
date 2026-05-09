package br.com.nora.api.application.analysis;

/**
 * Falhas no pipeline de analise. Sealed; cada subtipo carrega um {@link #code()} estavel mapeado
 * pelo GlobalExceptionHandler.
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

    /** Reuniao referenciada nao existe ou nao pertence ao tenant. */
    public static final class MeetingNotFound extends AnalysisException {
        public MeetingNotFound(java.util.UUID meetingId) {
            super("ANALYSIS_MEETING_NOT_FOUND", "meeting not found: " + meetingId);
        }
    }

    /** Transcript nao encontrado para o meeting (estado inconsistente). */
    public static final class TranscriptMissing extends AnalysisException {
        public TranscriptMissing(java.util.UUID meetingId) {
            super("ANALYSIS_TRANSCRIPT_MISSING", "transcript missing for meeting: " + meetingId);
        }
    }

    /** Worker indisponivel ou retornou erro. */
    public static final class WorkerUnavailable extends AnalysisException {
        public WorkerUnavailable(String detail, Throwable cause) {
            super("ANALYSIS_WORKER_UNAVAILABLE", "nlp worker unavailable: " + detail, cause);
        }
    }

    /** Worker respondeu mas o payload nao validou contra o schema esperado. */
    public static final class InvalidWorkerResponse extends AnalysisException {
        public InvalidWorkerResponse(String detail, Throwable cause) {
            super("ANALYSIS_INVALID_RESPONSE", "invalid worker response: " + detail, cause);
        }
    }
}
