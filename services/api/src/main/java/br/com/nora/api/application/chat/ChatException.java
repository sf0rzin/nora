package br.com.nora.api.application.chat;

/** Exceções de regra de negócio para o agregado "ChatSession" (histórico do assistente). */
public abstract class ChatException extends RuntimeException {

    private final String code;

    protected ChatException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final class NotFound extends ChatException {
        public NotFound() {
            super("CHAT_SESSION_NOT_FOUND", "chat session not found");
        }
    }

    public static final class InvalidTitle extends ChatException {
        public InvalidTitle() {
            super("CHAT_INVALID_TITLE", "chat session title cannot be blank");
        }
    }

    public static final class InvalidContent extends ChatException {
        public InvalidContent() {
            super("CHAT_INVALID_CONTENT", "chat message content cannot be blank");
        }
    }
}
