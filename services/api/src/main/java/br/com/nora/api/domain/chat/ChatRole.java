package br.com.nora.api.domain.chat;

/** Author of a chat message: the user or the NORA assistant. */
public enum ChatRole {
    USER,
    ASSISTANT;

    /** Converts the wire value ({@code "user"}/{@code "assistant"}) into the enum. */
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

    /** Wire value used in persistence and in the API ({@code "user"}/{@code "assistant"}). */
    public String wire() {
        return name().toLowerCase();
    }
}
