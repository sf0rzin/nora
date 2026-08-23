package br.com.nora.api.api.dto.meeting;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Metadata sent as multipart JSON (the "metadata" field) alongside the transcript file.
 *
 * <p>Two things were half-applied here and are now consistent. {@code participants} is declared as
 * {@code List<@Valid ...>}: without the element-level {@code @Valid}, Bean Validation does not
 * cascade into collection elements, so the {@code @NotBlank} on {@code displayName} below was never
 * evaluated — a blank name reached the domain constructor, which rejected it with a message about
 * an internal invariant instead of the field-level 400 every other bad field gets. And both list
 * fields now carry a cardinality cap, which {@code attributes} beside them always had: a cap on one
 * of three collections in the same record is a cap that was thought about once and then forgotten
 * twice.
 */
public record MeetingUploadMetadata(
        @NotBlank(message = "title is required") @Size(max = 200) String title,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        @Size(max = 20) String language,
        @JsonProperty("transcriptFormat") @NotBlank(message = "transcriptFormat is required")
                String transcriptFormat,
        @Size(max = 200, message = "participants must have at most 200 entries")
                List<@Valid ParticipantPayload> participants,
        @Size(max = 50, message = "tags must have at most 50 entries")
                List<@Size(max = 64) String> tags,
        @Size(max = 20, message = "attributes must have at most 20 entries")
                Map<@Size(max = 64) String, @Size(max = 256) String> attributes) {

    public record ParticipantPayload(
            @NotBlank(message = "participant displayName is required") @Size(max = 200)
                    String displayName,
            @Size(max = 254) String email,
            Boolean isInternal) {}
}
