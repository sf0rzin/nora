package br.com.nora.api.application.project;

/** Excecoes do dominio de projects. Mapeadas pelo GlobalExceptionHandler. */
public sealed class ProjectException extends RuntimeException permits ProjectException.NotFound {

    private final String code;

    protected ProjectException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final class NotFound extends ProjectException {
        public NotFound() {
            super("PROJECT_NOT_FOUND", "Project not found in this tenant.");
        }
    }
}
