package br.com.nora.api.application.iam;

/** Excecoes do subdominio IAM. Cada code mapeado para HTTP no GlobalExceptionHandler. */
public class IamException extends RuntimeException {

    private final String code;

    public IamException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static IamException groupNotFound() {
        return new IamException("IAM_GROUP_NOT_FOUND", "Grupo IAM nao encontrado.");
    }

    public static IamException policyNotFound() {
        return new IamException("IAM_POLICY_NOT_FOUND", "Policy IAM nao encontrada.");
    }

    public static IamException nameTaken(String name) {
        return new IamException("IAM_NAME_TAKEN", "Nome ja em uso: " + name);
    }

    public static IamException invalidDocument(String detail) {
        return new IamException("IAM_INVALID_DOCUMENT", "Documento de policy invalido: " + detail);
    }

    public static IamException forbidden(String action) {
        return new IamException("IAM_FORBIDDEN", "Acao nao permitida: " + action);
    }
}
