package br.com.nora.api.api.dto.meeting;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Metadata sent as multipart JSON (the "metadata" field) alongside the transcript file. */
public record MeetingUploadMetadata(
        @NotBlank(message = "title is required") @Size(max = 200) String title,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        String language,
        @JsonProperty("transcriptFormat") @NotBlank(message = "transcriptFormat is required")
                String transcriptFormat,
        List<ParticipantPayload> participants,
        List<String> tags,
        @Size(max = 20, message = "attributes must have at most 20 entries")
                Map<@Size(max = 64) String, @Size(max = 256) String> attributes) {

    public record ParticipantPayload(
            @NotBlank String displayName, String email, Boolean isInternal) {}
}
