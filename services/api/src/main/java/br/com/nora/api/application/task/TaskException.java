package br.com.nora.api.application.task;

/** Excecoes de regra de negocio para o agregado "Task" (action items extraidos). */
public abstract class TaskException extends RuntimeException {

    private final String code;

    protected TaskException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final class NotFound extends TaskException {
        public NotFound() {
            super("TASK_NOT_FOUND", "task not found");
        }
    }

    public static final class InvalidTitle extends TaskException {
        public InvalidTitle() {
            super("TASK_INVALID_TITLE", "task title cannot be blank");
        }
    }
}
