package br.com.nora.api.infrastructure.nlp;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * DTOs de transporte do endpoint {@code /split} do NLP worker (deteccao de fronteiras entre
 * reunioes concatenadas num unico arquivo). Mesmo papel do {@link WorkerDtos}: apenas estes objetos
 * cruzam a fronteira HTTP com o worker; nao vazam para dominio/persistencia.
 *
 * <p>{@code startLine}/{@code endLine} sao 1-based e inclusivos, calculados sobre o arquivo
 * ORIGINAL (a redacao de PII no worker e intra-linha, entao os numeros valem para o arquivo que o
 * usuario subiu). {@code preview} ja vem REDIGIDO pelo PII Shield do worker (ADR 0012).
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
