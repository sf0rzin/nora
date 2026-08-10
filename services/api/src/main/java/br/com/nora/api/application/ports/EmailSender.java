package br.com.nora.api.application.ports;

/**
 * Port for sending transactional e-mails. The default implementation in the MVP logs to the
 * console; in production a Resend bean is activated when RESEND_API_KEY is present.
 */
public interface EmailSender {

    void sendEmailVerification(String toEmail, String displayName, String verificationLink);

    void sendPasswordReset(String toEmail, String displayName, String resetLink);

    /**
     * Tells the owner of an address that a signup was attempted with it.
     *
     * <p>The public signup endpoint answers the same way whether or not the address already has an
     * account, so this message is the only channel where that fact surfaces — and it is delivered
     * to the mailbox that owns it. It carries no token and no link that grants access: the
     * recipient is only pointed at the sign-in page.
     *
     * @param toEmail owner of the address
     * @param displayName owner's name (rendered in the template)
     * @param signInUrl absolute URL of the sign-in page
     */
    void sendSignupAttemptOnExistingAccount(String toEmail, String displayName, String signInUrl);

    /**
     * Sends a user invitation (US06). The {@code subject} and the fields are parameterized by the
     * caller, which also injects the absolute link to the frontend. The adapter is only responsible
     * for delivering it via SMTP/Resend/SDK.
     *
     * @param toEmail recipient
     * @param tenantName friendly tenant name (rendered in the template)
     * @param invitedByName who invited (rendered in the template)
     * @param acceptUrl absolute URL with token for the frontend
     * @param expiresInDays how many days the invitation is valid for (rendered in the template)
     */
    void sendInvitation(
            String toEmail,
            String tenantName,
            String invitedByName,
            String acceptUrl,
            int expiresInDays);

    /**
     * Sends a NORA Flows workflow notification (subject + HTML body already assembled by the
     * action). Unlike the transactional e-mails above, FAILURE MUST PROPAGATE (exception): the
     * engine catches it and records the error in the execution log — faking success would break the
     * real history.
     */
    void sendWorkflowNotification(String toEmail, String subject, String htmlBody);
}
