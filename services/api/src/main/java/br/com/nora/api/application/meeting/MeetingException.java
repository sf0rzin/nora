package br.com.nora.api.application.meeting;

/** Exceptions of the meetings domain. Mapped by GlobalExceptionHandler. */
public sealed class MeetingException extends RuntimeException
        permits MeetingException.NotFound,
                MeetingException.TranscriptTooLarge,
                MeetingException.UnsupportedFormat,
                MeetingException.SplitUnsupportedFormat,
                MeetingException.FileTooLarge,
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

    /** Split-preview only accepts .txt for now (VTT/SRT have their own timestamps). */
    public static final class SplitUnsupportedFormat extends MeetingException {
        public SplitUnsupportedFormat() {
            super(
                    "SPLIT_UNSUPPORTED_FORMAT",
                    "Automatic split is only available for .txt for now.");
        }
    }

    /**
     * File above the upload limit. The message is user-facing (the MeetingException handler returns
     * {@code getMessage()} directly, unlike IllegalArgumentException which is masked for security).
     */
    public static final class FileTooLarge extends MeetingException {
        public FileTooLarge(int maxMegabytes) {
            super("FILE_TOO_LARGE", "The file exceeds the limit of " + maxMegabytes + " MB.");
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
