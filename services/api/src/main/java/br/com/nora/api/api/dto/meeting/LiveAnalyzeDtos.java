package br.com.nora.api.api.dto.meeting;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public final class LiveAnalyzeDtos {

    private LiveAnalyzeDtos() {}

    public record LiveAnalyzeRequest(
            @NotBlank @Size(min = 20, max = 500_000) @JsonProperty("transcriptChunk")
                    String transcriptChunk,
            @JsonProperty("previousHighlights") LiveHighlightsDto previousHighlights,
            @JsonProperty("language") String language) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LiveHighlightsDto(
            @JsonProperty("decisions") List<LiveHighlightItemDto> decisions,
            @JsonProperty("nextSteps") List<LiveHighlightItemDto> nextSteps,
            @JsonProperty("observations") List<LiveHighlightItemDto> observations,
            @JsonProperty("tasks") List<LiveTaskItemDto> tasks) {}

    public record LiveHighlightItemDto(
            @JsonProperty("text") String text,
            @JsonProperty("confidence") Double confidence,
            @JsonProperty("sourceQuote") String sourceQuote) {}

    public record LiveTaskItemDto(
            @JsonProperty("title") String title,
            @JsonProperty("assignee") String assignee,
            @JsonProperty("priority") String priority,
            @JsonProperty("sourceQuote") String sourceQuote) {}

    public record LiveAnalyzeResponse(
            @JsonProperty("decisions") List<LiveHighlightItemDto> decisions,
            @JsonProperty("nextSteps") List<LiveHighlightItemDto> nextSteps,
            @JsonProperty("observations") List<LiveHighlightItemDto> observations,
            @JsonProperty("tasks") List<LiveTaskItemDto> tasks,
            @JsonProperty("metadata") LiveAnalyzeMetadataDto metadata) {}

    public record LiveAnalyzeMetadataDto(
            @JsonProperty("processingMillis") Integer processingMillis,
            @JsonProperty("tokensInput") Integer tokensInput,
            @JsonProperty("tokensOutput") Integer tokensOutput,
            @JsonProperty("piiRedactionsApplied") Integer piiRedactionsApplied,
            @JsonProperty("modelVersion") String modelVersion) {}
}
