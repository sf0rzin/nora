package br.com.nora.api.infrastructure.persistence.chat;

import br.com.nora.api.application.ports.ChatSessionRepository;
import br.com.nora.api.domain.chat.ChatMessage;
import br.com.nora.api.domain.chat.ChatRole;
import br.com.nora.api.domain.chat.ChatSession;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter JDBC (via {@link EntityManager} + SQL nativo) das sessões de chat e mensagens (V022).
 * Mesmo estilo do {@code TaskRepositoryAdapter}: projeta linhas achatadas e escreve com SQL nativo,
 * sempre escopando por tenant_id (RLS, ADR 0028) + user_id. {@code @Transactional} para que, sob RLS
 * enforce, o aspect aplique o GUC de tenant nas leituras/escritas das tabelas enforced.
 */
@Repository
public class ChatSessionRepositoryAdapter implements ChatSessionRepository {

    private static final int LAST_SNIPPET_MAX = 140;

    @PersistenceContext private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ChatSessionSummaryRow> listByUser(UUID tenantId, UUID userId) {
        String sql =
                "SELECT s.id, s.tenant_id, s.user_id, s.title, s.created_at, s.updated_at, "
                        + "       (SELECT COUNT(*) FROM chat_message m "
                        + "          WHERE m.session_id = s.id AND m.tenant_id = s.tenant_id) AS msg_count, "
                        + "       (SELECT m2.content FROM chat_message m2 "
                        + "          WHERE m2.session_id = s.id AND m2.tenant_id = s.tenant_id "
                        + "          ORDER BY m2.created_at DESC, m2.id DESC LIMIT 1) AS last_snippet "
                        + "FROM chat_session s "
                        + "WHERE s.tenant_id = :tenantId AND s.user_id = :userId "
                        + "ORDER BY s.updated_at DESC, s.created_at DESC";
        var query = em.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("userId", userId);
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        List<ChatSessionSummaryRow> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            ChatSession session = toSession(r);
            int messageCount = ((Number) r[6]).intValue();
            String lastSnippet = snippet((String) r[7]);
            result.add(new ChatSessionSummaryRow(session, messageCount, lastSnippet));
        }
        return result;
    }

    @Override
    @Transactional
    public void create(ChatSession session) {
        em.createNativeQuery(
                        "INSERT INTO chat_session (id, tenant_id, user_id, title, created_at, updated_at) "
                                + "VALUES (:id, :tenantId, :userId, :title, :createdAt, :updatedAt)")
                .setParameter("id", session.id())
                .setParameter("tenantId", session.tenantId())
                .setParameter("userId", session.ownerUserId())
                .setParameter("title", session.title())
                .setParameter("createdAt", toTimestamp(session.createdAt()))
                .setParameter("updatedAt", toTimestamp(session.updatedAt()))
                .executeUpdate();
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public Optional<ChatSession> findByIdForUser(UUID id, UUID tenantId, UUID userId) {
        String sql =
                "SELECT s.id, s.tenant_id, s.user_id, s.title, s.created_at, s.updated_at "
                        + "FROM chat_session s "
                        + "WHERE s.id = :id AND s.tenant_id = :tenantId AND s.user_id = :userId";
        var query = em.createNativeQuery(sql);
        query.setParameter("id", id);
        query.setParameter("tenantId", tenantId);
        query.setParameter("userId", userId);
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toSession(rows.get(0)));
    }

    @Override
    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<ChatMessage> listMessages(UUID sessionId, UUID tenantId) {
        String sql =
                "SELECT m.id, m.session_id, m.tenant_id, m.role, m.content, m.created_at "
                        + "FROM chat_message m "
                        + "WHERE m.session_id = :sessionId AND m.tenant_id = :tenantId "
                        + "ORDER BY m.created_at ASC, m.id ASC";
        var query = em.createNativeQuery(sql);
        query.setParameter("sessionId", sessionId);
        query.setParameter("tenantId", tenantId);
        List<Object[]> rows = (List<Object[]>) query.getResultList();
        List<ChatMessage> result = new ArrayList<>(rows.size());
        for (Object[] r : rows) {
            result.add(toMessage(r));
        }
        return result;
    }

    @Override
    @Transactional
    public void appendMessage(ChatMessage message) {
        em.createNativeQuery(
                        "INSERT INTO chat_message (id, session_id, tenant_id, role, content, created_at) "
                                + "VALUES (:id, :sessionId, :tenantId, :role, :content, :createdAt)")
                .setParameter("id", message.id())
                .setParameter("sessionId", message.sessionId())
                .setParameter("tenantId", message.tenantId())
                .setParameter("role", message.role().wire())
                .setParameter("content", message.content())
                .setParameter("createdAt", toTimestamp(message.createdAt()))
                .executeUpdate();
    }

    @Override
    @Transactional
    public void updateTitle(
            UUID id, UUID tenantId, UUID userId, String title, OffsetDateTime updatedAt) {
        em.createNativeQuery(
                        "UPDATE chat_session SET title = :title, updated_at = :updatedAt "
                                + "WHERE id = :id AND tenant_id = :tenantId AND user_id = :userId")
                .setParameter("title", title)
                .setParameter("updatedAt", toTimestamp(updatedAt))
                .setParameter("id", id)
                .setParameter("tenantId", tenantId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void touch(UUID id, UUID tenantId, UUID userId, OffsetDateTime updatedAt) {
        em.createNativeQuery(
                        "UPDATE chat_session SET updated_at = :updatedAt "
                                + "WHERE id = :id AND tenant_id = :tenantId AND user_id = :userId")
                .setParameter("updatedAt", toTimestamp(updatedAt))
                .setParameter("id", id)
                .setParameter("tenantId", tenantId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    @Override
    @Transactional
    public void delete(UUID id, UUID tenantId, UUID userId) {
        em.createNativeQuery(
                        "DELETE FROM chat_session "
                                + "WHERE id = :id AND tenant_id = :tenantId AND user_id = :userId")
                .setParameter("id", id)
                .setParameter("tenantId", tenantId)
                .setParameter("userId", userId)
                .executeUpdate();
    }

    private ChatSession toSession(Object[] r) {
        UUID id = (UUID) r[0];
        UUID tenantId = (UUID) r[1];
        UUID userId = (UUID) r[2];
        String title = (String) r[3];
        OffsetDateTime createdAt = toOffset(r[4]);
        OffsetDateTime updatedAt = toOffset(r[5]);
        return new ChatSession(id, tenantId, userId, title, createdAt, updatedAt);
    }

    private ChatMessage toMessage(Object[] r) {
        UUID id = (UUID) r[0];
        UUID sessionId = (UUID) r[1];
        UUID tenantId = (UUID) r[2];
        ChatRole role = ChatRole.fromWire((String) r[3]);
        String content = (String) r[4];
        OffsetDateTime createdAt = toOffset(r[5]);
        return new ChatMessage(id, sessionId, tenantId, role, content, createdAt);
    }

    private static OffsetDateTime toOffset(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        return ((Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
    }

    private static Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    private static String snippet(String content) {
        if (content == null) {
            return null;
        }
        String normalized = content.strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() <= LAST_SNIPPET_MAX) {
            return normalized;
        }
        return normalized.substring(0, LAST_SNIPPET_MAX - 1).stripTrailing() + "…";
    }
}
