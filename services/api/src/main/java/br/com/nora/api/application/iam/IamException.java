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

    /**
     * The caller tried to set or remove ITS OWN permission boundary. Refused because a principal
     * that can edit its own cap does not have one (US44, ADR 0049 §4) — the whole point of a
     * boundary is that widening it is somebody else's decision.
     */
    public static IamException boundarySelfManaged() {
        return new IamException(
                "IAM_BOUNDARY_SELF", "A user cannot set or remove its own permission boundary.");
    }

    /**
     * A boundary was aimed at the tenant Root. Refused rather than stored, because the Root bypass
     * is applied before any statement is consulted (ADR 0049 §2): the row would sit in the table
     * looking like a control and cap nothing.
     */
    public static IamException boundaryOnRoot() {
        return new IamException(
                "IAM_BOUNDARY_ON_ROOT",
                "The tenant Root cannot be capped by a permission boundary.");
    }

    /**
     * A policy currently acting as somebody's permission boundary cannot be deleted. Deleting it
     * would remove a cap through an endpoint that says nothing about caps, which is a privilege
     * escalation with no audit trail naming it as one — detach the boundary first.
     */
    public static IamException policyInUseAsBoundary() {
        return new IamException(
                "IAM_POLICY_IN_USE_AS_BOUNDARY",
                "Policy is in use as a permission boundary. Remove the boundary first.");
    }
}
