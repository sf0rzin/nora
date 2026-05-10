package br.com.nora.api.application.meeting;

/** Excecoes do dominio de meetings. Mapeadas pelo GlobalExceptionHandler. */
public sealed class MeetingException extends RuntimeException
        permits MeetingException.NotFound,
                MeetingException.TranscriptTooLarge,
                MeetingException.UnsupportedFormat,
                MeetingException.EmptyTranscript,
                MeetingException.CannotReprocess {

    private final String code;

    protected MeetingException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final class NotFound extends MeetingException {
        public NotFound() {
            super("MEETING_NOT_FOUND", "Meeting not found in this tenant.");
        }
    }

    public static final class TranscriptTooLarge extends MeetingException {
        public TranscriptTooLarge(int max) {
            super(
                    "TRANSCRIPT_TOO_LARGE",
                    "Transcript exceeds the maximum allowed size of " + max + " characters.");
        }
    }

    public static final class UnsupportedFormat extends MeetingException {
        public UnsupportedFormat(String raw) {
            super("UNSUPPORTED_TRANSCRIPT_FORMAT", "Unsupported transcript format: " + raw);
        }
    }

    public static final class EmptyTranscript extends MeetingException {
        public EmptyTranscript() {
            super("EMPTY_TRANSCRIPT", "Transcript file is empty.");
        }
    }

    public static final class CannotReprocess extends MeetingException {
        public CannotReprocess(String reason) {
            super("CANNOT_REPROCESS", reason);
        }
    }
}
