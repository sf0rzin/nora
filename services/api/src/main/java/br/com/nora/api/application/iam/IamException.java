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
        return new IamException("IAM_AUTHORIZATION_NOT_DECLARED", "Action not allowed.");
    }

    /**
     * The user named in the request is not a user of the caller's tenant. 404 and not 403 on
     * purpose: a 403 would confirm that the id belongs to somebody, somewhere.
     */
    public static IamException userNotFound() {
        return new IamException("IAM_USER_NOT_FOUND", "IAM user not found.");
    }

    /**
     * Target user of the binding does not belong to the caller's tenant. Raised when translating
     * the composite FK violation from V027 -- there is no legitimate path that produces it.
     */
    public static IamException userNotInTenant() {
        return new IamException("IAM_USER_NOT_IN_TENANT", "User does not belong to this tenant.");
    }
}
