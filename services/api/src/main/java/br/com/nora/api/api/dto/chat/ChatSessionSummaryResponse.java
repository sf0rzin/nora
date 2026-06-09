package br.com.nora.api.api.dto.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Item da listagem de sessões de chat (sidebar) e corpo de criação/renomeação. Ordenado por {@code
 * updatedAt} desc na listagem. {@code createdAt} é incluído na resposta de criação; {@code
 * lastSnippet} é omitido quando vazio.
 */
@JsonInclude(Include.NON_NULL)
public record ChatSessionSummaryResponse(
        UUID id,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int messageCount,
        String lastSnippet) {}
