package br.com.nora.api.infrastructure.email;

import br.com.nora.api.application.ports.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Default implementation in dev: prints the links to the console. Lets you test the full flow
 * (signup, verification, reset) without configuring an external provider.
 *
 * <p>In production just set {@code RESEND_API_KEY} so that {@link ResendEmailSender} replaces this
 * bean.
 */
@Component
@ConditionalOnMissingBean(name = "resendEmailSender")
public class LogEmailSender implements EmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(LogEmailSender.class);

    @Override
    public void sendEmailVerification(String toEmail, String displayName, String verificationLink) {
        // Token present in verificationLink: we do not log the full URL to avoid exposing
        // credentials in centralized logs (Application Insights / Log Analytics). LGPD + ADR 0012.
        LOG.info(
                "[email/dev] verification -> to={} name={} (link suppressed; set RESEND_API_KEY"
                        + " for real sending)",
                toEmail,
                displayName);
    }

    @Override
    public void sendPasswordReset(String toEmail, String displayName, String resetLink) {
        // Token present in resetLink: we do not log the full URL to avoid exposing credentials in
        // centralized logs (Application Insights / Log Analytics). LGPD + ADR 0012.
        LOG.info(
                "[email/dev] password-reset -> to={} name={} (link suppressed; set"
                        + " RESEND_API_KEY for real sending)",
                toEmail,
                displayName);
    }

    @Override
    public void sendSignupAttemptOnExistingAccount(
            String toEmail, String displayName, String signInUrl) {
        LOG.info(
                "[email/dev] signup-attempt-on-existing-account -> to={} name={} (set"
                        + " RESEND_API_KEY for real delivery)",
                toEmail,
                displayName);
    }

    @Override
    public void sendInvitation(
            String toEmail,
            String tenantName,
            String invitedByName,
            String acceptUrl,
            int expiresInDays) {
        // Token present in acceptUrl: we do not log the full URL to avoid exposing PII in logs.
        LOG.info(
                "[email/dev] invitation -> to={} tenant={} invitedBy={} expiresInDays={}",
                toEmail,
                tenantName,
                invitedByName,
                expiresInDays);
    }

    @Override
    public void sendWorkflowNotification(String toEmail, String subject, String htmlBody) {
        // Body suppressed: may contain the meeting summary (business data) — does not go to logs.
        LOG.info(
                "[email/dev] workflow-notification -> to={} subject={} (body suppressed; set"
                        + " RESEND_API_KEY for real sending)",
                toEmail,
                subject);
    }

    /** Marker so Spring loads the package. */
    @Configuration
    static class Marker {}
}
