package br.com.nora.api.application.iam;

/**
 * Failures of the invite flow (US06, ADR 0011). Each {@code code} maps to an HTTP status in the
 * {@code GlobalExceptionHandler}.
 */
public class InvitationException extends RuntimeException {

    private final String code;

    public InvitationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static InvitationException emailDomainNotAllowed(String email, String allowedDomain) {
        return new InvitationException(
                "EMAIL_DOMAIN_NOT_ALLOWED",
                "Email '" + email + "' does not belong to the allowed domain '" + allowedDomain + "'.");
    }

    public static InvitationException inviteNotFound() {
        return new InvitationException("INVITE_NOT_FOUND", "Invitation not found.");
    }

    public static InvitationException inviteAlreadyAccepted() {
        return new InvitationException(
                "INVITE_ALREADY_ACCEPTED", "Invitation was already accepted or revoked.");
    }

    public static InvitationException inviteExpired() {
        return new InvitationException("INVITE_EXPIRED", "Invitation expired.");
    }

    public static InvitationException duplicatePending(String email) {
        return new InvitationException(
                "INVITE_DUPLICATE_PENDING",
                "There is already a pending invitation for '" + email + "'.");
    }
}
