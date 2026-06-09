package br.com.nora.api.domain.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Sessão de chat do assistente NORA. Pertence a um usuário (ownerUserId) dentro de um tenant
 * (tenantId, ADR 0002). O título pode ser nulo até a primeira mensagem do usuário, de onde é
 * derivado. As mensagens vivem em {@link ChatMessage} (referência por sessionId) para evitar
 * carregar a conversa inteira nas listagens. Imutável.
 */
public record ChatSession(
        UUID id,
        UUID tenantId,
        UUID ownerUserId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** Tamanho máximo do título derivado da primeira mensagem do usuário. */
    public static final int DERIVED_TITLE_MAX = 48;

    /**
     * Deriva um título curto a partir do conteúdo da primeira mensagem do usuário: colapsa espaços,
     * corta em {@value #DERIVED_TITLE_MAX} caracteres (com reticências quando trunca). Retorna
     * {@code null} para conteúdo vazio — o título permanece indefinido nesse caso.
     */
    public static String deriveTitle(String firstUserMessage) {
        if (firstUserMessage == null) {
            return null;
        }
        String normalized = firstUserMessage.strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() <= DERIVED_TITLE_MAX) {
            return normalized;
        }
        return normalized.substring(0, DERIVED_TITLE_MAX - 1).stripTrailing() + "…";
    }
}
