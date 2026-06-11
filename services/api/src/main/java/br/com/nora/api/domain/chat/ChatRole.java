package br.com.nora.api.domain.chat;

/** Autor de uma mensagem de chat: o usuário ou o assistente NORA. */
public enum ChatRole {
    USER,
    ASSISTANT;

    /** Converte o valor de transporte ({@code "user"}/{@code "assistant"}) no enum. */
    public static ChatRole fromWire(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("role is required");
        }
        return switch (raw.trim().toLowerCase()) {
            case "user" -> USER;
            case "assistant" -> ASSISTANT;
            default -> throw new IllegalArgumentException("invalid role: " + raw);
        };
    }

    /** Valor de transporte usado na persistência e na API ({@code "user"}/{@code "assistant"}). */
    public String wire() {
        return name().toLowerCase();
    }
}
