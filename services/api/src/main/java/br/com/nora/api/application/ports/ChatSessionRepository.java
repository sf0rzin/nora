package br.com.nora.api.application.ports;

import br.com.nora.api.domain.chat.ChatMessage;
import br.com.nora.api.domain.chat.ChatSession;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acesso de leitura/escrita às sessões de chat e suas mensagens. Toda operação é escopada por
 * tenant_id (ADR 0002) + user_id (cada usuário só acessa as próprias sessões).
 */
public interface ChatSessionRepository {

    /**
     * Visão de listagem da sidebar: a sessão somada à contagem de mensagens e ao trecho da última
     * mensagem. Achatada para evitar carregar todas as mensagens só para a lista.
     */
    record ChatSessionSummaryRow(ChatSession session, int messageCount, String lastSnippet) {}

    /** Sessões do usuário no tenant, mais recentes primeiro (por updated_at desc). */
    List<ChatSessionSummaryRow> listByUser(UUID tenantId, UUID userId);

    /** Cria uma nova sessão (id já gerado no domínio/serviço). */
    void create(ChatSession session);

    /** Sessão por id, restrita ao tenant + usuário dono. */
    Optional<ChatSession> findByIdForUser(UUID id, UUID tenantId, UUID userId);

    /** Mensagens de uma sessão, em ordem cronológica (created_at asc). */
    List<ChatMessage> listMessages(UUID sessionId, UUID tenantId);

    /** Anexa uma mensagem (id já gerado). Não bumpa updated_at — o serviço o faz explicitamente. */
    void appendMessage(ChatMessage message);

    /** Atualiza o título e bumpa updated_at para {@code updatedAt}. */
    void updateTitle(UUID id, UUID tenantId, UUID userId, String title, OffsetDateTime updatedAt);

    /** Bumpa updated_at para {@code updatedAt} (chamado ao anexar mensagem). */
    void touch(UUID id, UUID tenantId, UUID userId, OffsetDateTime updatedAt);

    /** Remove a sessão (mensagens caem por ON DELETE CASCADE). */
    void delete(UUID id, UUID tenantId, UUID userId);
}
