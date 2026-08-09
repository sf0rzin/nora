package br.com.nora.api.api.dto.meeting;

import java.util.List;

/**
 * DTOs of the {@code POST /meetings/split-preview} endpoint: preview of the meeting boundaries
 * detected in a single .txt file. Mirrors the worker {@code /split} contract in camelCase.
 *
 * <p>{@code startLine}/{@code endLine} are 1-based and inclusive over the ORIGINAL file — the
 * actual slicing is done client-side after user confirmation. {@code preview} already arrives
 * redacted by the worker's PII Shield (never raw PII — ADR 0012).
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
