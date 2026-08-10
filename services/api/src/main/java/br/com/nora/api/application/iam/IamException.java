package br.com.nora.api.application.iam;

/** Exceptions of the IAM subdomain. Each code mapped to HTTP in the GlobalExceptionHandler. */
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
        return new IamException("IAM_GROUP_NOT_FOUND", "IAM group not found.");
    }

    public static IamException policyNotFound() {
        return new IamException("IAM_POLICY_NOT_FOUND", "IAM policy not found.");
    }

    public static IamException nameTaken(String name) {
        return new IamException("IAM_NAME_TAKEN", "Name already in use: " + name);
    }

    public static IamException invalidDocument(String detail) {
        return new IamException("IAM_INVALID_DOCUMENT", "Invalid policy document: " + detail);
    }

    public static IamException forbidden(String action) {
        return new IamException("IAM_FORBIDDEN", "Action not allowed: " + action);
    }

    /**
     * A controller handler reached the request pipeline without declaring an authorization decision
     * (neither the permission annotation nor the justified opt-out). Distinct from {@link
     * #forbidden} so the logs separate a coding mistake from an ordinary policy denial; the
     * offending handler is named in the log, never in the response.
     */
    public static IamException authorizationNotDeclared() {
        return new IamException("IAM_AUTHORIZATION_NOT_DECLARED", "Acao nao permitida.");
    }

    /**
     * Target user of the binding does not belong to the caller's tenant. Raised when translating
     * the composite FK violation from V027 -- there is no legitimate path that produces it.
     */
    public static IamException userNotInTenant() {
        return new IamException("IAM_USER_NOT_IN_TENANT", "User does not belong to this tenant.");
    }
}
