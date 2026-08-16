package br.com.nora.api.application.task;

/** Business-rule exceptions for the "Task" aggregate (extracted action items). */
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

    /** The raw value is not echoed back: the code and the expected shape are enough. */
    public static final class InvalidDueDate extends TaskException {
        public InvalidDueDate() {
            super(
                    "TASK_INVALID_DUE_DATE",
                    "task due date must be an ISO date (yyyy-MM-dd), or empty to clear it");
        }
    }
}
