package br.com.nora.api.api.dto.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Item of the chat session listing (sidebar) and body of create/rename. Ordered by {@code
 * updatedAt} desc in the listing. {@code createdAt} is included in the create response; {@code
 * lastSnippet} is omitted when empty.
 */
@JsonInclude(Include.NON_NULL)
public record ChatSessionSummaryResponse(
        UUID id,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int messageCount,
        String lastSnippet) {}
