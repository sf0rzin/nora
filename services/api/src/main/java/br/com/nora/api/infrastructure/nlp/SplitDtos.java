package br.com.nora.api.infrastructure.nlp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Transport DTOs for the NLP worker's {@code /split} endpoint (detection of boundaries between
 * meetings concatenated into a single file). Same role as {@link WorkerDtos}: only these objects
 * cross the HTTP boundary with the worker; they do not leak into domain/persistence.
 *
 * <p>{@code startLine}/{@code endLine} are 1-based and inclusive, computed over the ORIGINAL file
 * (PII redaction in the worker is intra-line, so the numbers hold for the file the user uploaded).
 * {@code preview} already arrives REDACTED by the worker's PII Shield (ADR 0012).
 */
public final class SplitDtos {

    private SplitDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SplitRequest(
            @JsonProperty("transcript") String transcript,
            @JsonProperty("language") String language) {}

    public record SegmentDto(
            @JsonProperty("index") Integer index,
            @JsonProperty("title") String title,
            @JsonProperty("startLine") Integer startLine,
            @JsonProperty("endLine") Integer endLine,
            @JsonProperty("confidence") Double confidence,
            @JsonProperty("preview") String preview) {}

    public record SplitMetadata(
            @JsonProperty("modelVersion") String modelVersion,
            @JsonProperty("promptVersion") String promptVersion,
            @JsonProperty("tokensInput") Integer tokensInput,
            @JsonProperty("tokensOutput") Integer tokensOutput,
            @JsonProperty("processingMillis") Integer processingMillis,
            @JsonProperty("piiRedactionsApplied") Integer piiRedactionsApplied) {}

    public record SplitResponse(
            @JsonProperty("segments") List<SegmentDto> segments,
            @JsonProperty("totalLines") Integer totalLines,
            @JsonProperty("metadata") SplitMetadata metadata) {}
}
