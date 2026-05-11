package br.com.nora.api.api.dto.meeting;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Metadados enviados como JSON multipart (campo "metadata") junto ao arquivo da transcricao. */
public record MeetingUploadMetadata(
        @NotBlank(message = "title is required") @Size(max = 200) String title,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        String language,
        @JsonProperty("transcriptFormat") @NotBlank(message = "transcriptFormat is required")
                String transcriptFormat,
        List<ParticipantPayload> participants,
        List<String> tags,
        Map<String, String> attributes) {

    public record ParticipantPayload(
            @NotBlank String displayName, String email, Boolean isInternal) {}
}
