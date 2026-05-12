package br.com.nora.api.infrastructure.email;

import br.com.nora.api.application.ports.EmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/**
 * Implementacao default em dev: imprime os links no console. Permite que voce teste o fluxo
 * completo (signup, verificacao, reset) sem configurar provider externo.
 *
 * <p>Em producao basta setar {@code RESEND_API_KEY} para que o {@link ResendEmailSender} substitua
 * este bean.
 */
@Component
@ConditionalOnMissingBean(name = "resendEmailSender")
public class LogEmailSender implements EmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(LogEmailSender.class);

    @Override
    public void sendEmailVerification(String toEmail, String displayName, String verificationLink) {
        LOG.info(
                "[email/dev] verification -> to={} name={} link={}",
                toEmail,
                displayName,
                verificationLink);
    }

    @Override
    public void sendPasswordReset(String toEmail, String displayName, String resetLink) {
        LOG.info(
                "[email/dev] password-reset -> to={} name={} link={}",
                toEmail,
                displayName,
                resetLink);
    }

    @Override
    public void sendInvitation(
            String toEmail,
            String tenantName,
            String invitedByName,
            String acceptUrl,
            int expiresInDays) {
        // Token presente no acceptUrl: nao logamos a URL completa para nao expor PII em logs.
        LOG.info(
                "[email/dev] invitation -> to={} tenant={} invitedBy={} expiresInDays={}",
                toEmail,
                tenantName,
                invitedByName,
                expiresInDays);
    }

    /** Marker para Spring carregar o pacote. */
    @Configuration
    static class Marker {}
}
