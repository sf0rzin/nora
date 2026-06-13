package br.com.nora.api.api.dto.meeting;

import java.util.List;

/**
 * DTOs do endpoint {@code POST /meetings/split-preview}: preview das fronteiras de reunioes
 * detectadas num arquivo .txt unico. Espelha o contrato do worker {@code /split} em camelCase.
 *
 * <p>{@code startLine}/{@code endLine} sao 1-based e inclusivos sobre o arquivo ORIGINAL — o
 * fatiamento real e feito client-side apos confirmacao do usuario. {@code preview} ja vem redigido
 * pelo PII Shield do worker (nunca PII crua — ADR 0012).
 */
public final class SplitPreviewDtos {

    private SplitPreviewDtos() {}

    public record SplitPreviewResponse(
            List<SegmentDto> segments, int totalLines, MetadataDto metadata) {}

    public record SegmentDto(
            int index,
            String title,
            int startLine,
            int endLine,
            double confidence,
            String preview) {}

    public record MetadataDto(
            String modelVersion,
            String promptVersion,
            int tokensInput,
            int tokensOutput,
            int processingMillis,
            int piiRedactionsApplied) {}
}
