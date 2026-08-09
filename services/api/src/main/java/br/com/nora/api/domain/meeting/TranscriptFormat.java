package br.com.nora.api.domain.meeting;

import java.util.Arrays;

/** Supported format for text transcripts (US07). */
public enum TranscriptFormat {
    TXT,
    VTT,
    SRT;

    public static TranscriptFormat fromString(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("transcript format is required");
        }
        String normalized = raw.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(f -> f.name().equals(normalized))
                .findFirst()
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "unsupported transcript format: " + raw));
    }
}
