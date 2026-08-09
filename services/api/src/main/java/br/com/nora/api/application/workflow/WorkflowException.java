package br.com.nora.api.application.workflow;

/** Business-rule exceptions of the "Workflow" aggregate (NORA Flows). */
public abstract class WorkflowException extends RuntimeException {

    private final String code;

    protected WorkflowException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final class NotFound extends WorkflowException {
        public NotFound() {
            super("WORKFLOW_NOT_FOUND", "workflow not found");
        }
    }

    public static final class InvalidDefinition extends WorkflowException {
        public InvalidDefinition(String detail) {
            super("WORKFLOW_INVALID_DEFINITION", detail);
        }
    }

    public static final class InvalidName extends WorkflowException {
        public InvalidName() {
            super("WORKFLOW_INVALID_NAME", "workflow name cannot be blank");
        }
    }
}
