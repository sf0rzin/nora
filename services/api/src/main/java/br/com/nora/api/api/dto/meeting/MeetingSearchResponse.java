package br.com.nora.api.api.dto.meeting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Semantic search (RAG) result: meetings most relevant to the query, ordered by similarity. */
public record MeetingSearchResponse(List<Item> items) {

    public record Item(
            UUID id,
            String title,
            String summarySnippet,
            OffsetDateTime startedAt,
            String processingStatus) {}
}
