package br.com.nora.api.api.dto.meeting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Resultado da busca semântica (RAG): reuniões mais relevantes à query, ordenadas por similaridade.
 */
public record MeetingSearchResponse(List<Item> items) {

    public record Item(
            UUID id,
            String title,
            String summarySnippet,
            OffsetDateTime startedAt,
            String processingStatus) {}
}
