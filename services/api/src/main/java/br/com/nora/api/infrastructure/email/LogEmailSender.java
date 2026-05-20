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
        // Token presente em verificationLink: nao logamos a URL completa para nao expor credenciais
        // em logs centralizados (Application Insights / Log Analytics). LGPD + ADR 0012.
        LOG.info(
                "[email/dev] verification -> to={} name={} (link suprimido; setar RESEND_API_KEY"
                        + " para envio real)",
                toEmail,
                displayName);
    }

    @Override
    public void sendPasswordReset(String toEmail, String displayName, String resetLink) {
        // Token presente em resetLink: nao logamos a URL completa para nao expor credenciais em
        // logs centralizados (Application Insights / Log Analytics). LGPD + ADR 0012.
        LOG.info(
                "[email/dev] password-reset -> to={} name={} (link suprimido; setar"
                        + " RESEND_API_KEY para envio real)",
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
