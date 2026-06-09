package br.com.nora.api.application.chat;

import br.com.nora.api.application.ports.ChatSessionRepository;
import br.com.nora.api.application.ports.ChatSessionRepository.ChatSessionSummaryRow;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.domain.chat.ChatMessage;
import br.com.nora.api.domain.chat.ChatRole;
import br.com.nora.api.domain.chat.ChatSession;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serviço das sessões de chat do assistente NORA. Toda operação é escopada pelo tenant_id + user_id
 * do principal: um usuário só enxerga e manipula as próprias sessões. Regras de negócio: derivar o
 * título da 1ª mensagem do usuário quando a sessão ainda não tem título; bumpar updated_at sempre
 * que uma mensagem é anexada.
 */
@Service
public class ChatSessionService {

    private final ChatSessionRepository sessions;
    private final Clock clock;

    public ChatSessionService(ChatSessionRepository sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    /** Detalhe de uma sessão: a sessão somada às suas mensagens em ordem cronológica. */
    public record SessionDetail(ChatSession session, List<ChatMessage> messages) {}

    private OffsetDateTime now() {
        return clock.now().atOffset(ZoneOffset.UTC);
    }

    @Transactional(readOnly = true)
    public List<ChatSessionSummaryRow> list(UUID tenantId, UUID userId) {
        return sessions.listByUser(tenantId, userId);
    }

    @Transactional
    public ChatSession create(UUID tenantId, UUID userId, String title) {
        OffsetDateTime now = now();
        String trimmed = title == null || title.isBlank() ? null : title.trim();
        ChatSession session =
                new ChatSession(UUID.randomUUID(), tenantId, userId, trimmed, now, now);
        sessions.create(session);
        return session;
    }

    @Transactional(readOnly = true)
    public SessionDetail get(UUID id, UUID tenantId, UUID userId) {
        ChatSession session =
                sessions.findByIdForUser(id, tenantId, userId)
                        .orElseThrow(ChatException.NotFound::new);
        List<ChatMessage> messages = sessions.listMessages(id, tenantId);
        return new SessionDetail(session, messages);
    }

    /**
     * Anexa uma mensagem à sessão. Bumpa updated_at; se a sessão estiver sem título e a mensagem
     * for do usuário, deriva o título do conteúdo (~48 chars).
     */
    @Transactional
    public ChatMessage appendMessage(
            UUID sessionId, UUID tenantId, UUID userId, ChatRole role, String content) {
        if (content == null || content.isBlank()) {
            throw new ChatException.InvalidContent();
        }
        ChatSession session =
                sessions.findByIdForUser(sessionId, tenantId, userId)
                        .orElseThrow(ChatException.NotFound::new);

        OffsetDateTime now = now();
        ChatMessage message =
                new ChatMessage(UUID.randomUUID(), sessionId, tenantId, role, content.strip(), now);
        sessions.appendMessage(message);

        String derivedTitle = null;
        if (role == ChatRole.USER && (session.title() == null || session.title().isBlank())) {
            derivedTitle = ChatSession.deriveTitle(content);
        }
        if (derivedTitle != null) {
            sessions.updateTitle(sessionId, tenantId, userId, derivedTitle, now);
        } else {
            sessions.touch(sessionId, tenantId, userId, now);
        }
        return message;
    }

    @Transactional
    public ChatSession rename(UUID id, UUID tenantId, UUID userId, String title) {
        if (title == null || title.isBlank()) {
            throw new ChatException.InvalidTitle();
        }
        sessions.findByIdForUser(id, tenantId, userId).orElseThrow(ChatException.NotFound::new);
        sessions.updateTitle(id, tenantId, userId, title.trim(), now());
        return sessions.findByIdForUser(id, tenantId, userId)
                .orElseThrow(ChatException.NotFound::new);
    }

    @Transactional
    public void delete(UUID id, UUID tenantId, UUID userId) {
        sessions.findByIdForUser(id, tenantId, userId).orElseThrow(ChatException.NotFound::new);
        sessions.delete(id, tenantId, userId);
    }
}
