package br.com.nora.api.application.ports;

import br.com.nora.api.domain.chat.ChatMessage;
import br.com.nora.api.domain.chat.ChatSession;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read/write access to chat sessions and their messages. Every operation is scoped by tenant_id
 * (ADR 0002) + user_id (each user only accesses their own sessions).
 */
public interface ChatSessionRepository {

    /**
     * Sidebar listing view: the session plus the message count and the snippet of the last message.
     * Flattened to avoid loading all messages just for the list.
     */
    record ChatSessionSummaryRow(ChatSession session, int messageCount, String lastSnippet) {}

    /** The user's sessions in the tenant, most recent first (by updated_at desc). */
    List<ChatSessionSummaryRow> listByUser(UUID tenantId, UUID userId);

    /** Creates a new session (id already generated in the domain/service). */
    void create(ChatSession session);

    /** Session by id, restricted to the tenant + owning user. */
    Optional<ChatSession> findByIdForUser(UUID id, UUID tenantId, UUID userId);

    /** Messages of a session, in chronological order (created_at asc). */
    List<ChatMessage> listMessages(UUID sessionId, UUID tenantId);

    /**
     * Appends a message (id already generated). Does not bump updated_at — the service does it
     * explicitly.
     */
    void appendMessage(ChatMessage message);

    /** Updates the title and bumps updated_at to {@code updatedAt}. */
    void updateTitle(UUID id, UUID tenantId, UUID userId, String title, OffsetDateTime updatedAt);

    /** Bumps updated_at to {@code updatedAt} (called when appending a message). */
    void touch(UUID id, UUID tenantId, UUID userId, OffsetDateTime updatedAt);

    /** Removes the session (messages go away via ON DELETE CASCADE). */
    void delete(UUID id, UUID tenantId, UUID userId);
}
