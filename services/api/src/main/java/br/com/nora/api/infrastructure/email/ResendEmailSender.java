package br.com.nora.api.infrastructure.email;

import br.com.nora.api.application.ports.EmailSender;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Adapter for the Resend API (https://resend.com/docs/api-reference/emails/send-email).
 *
 * <p>Enabled when {@code RESEND_API_KEY} is present OR {@code nora.email.provider=resend}.
 * Otherwise {@link LogEmailSender} stays as the default and nothing external is called.
 *
 * <p>Free tier (3,000 e-mails/month) covers the whole MVP. Real costs: zero until launch.
 */
@Component(value = "resendEmailSender")
@ConditionalOnExpression("'${nora.email.resend.api-key:}'.length() > 0")
public class ResendEmailSender implements EmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(ResendEmailSender.class);
    private static final String RESEND_URL = "https://api.resend.com/emails";
    private static final java.time.Duration TIMEOUT = java.time.Duration.ofSeconds(10);

    private final WebClient http;
    private final String fromAddress;

    public ResendEmailSender(
            @Value("${nora.email.resend.api-key}") String apiKey,
            @Value("${nora.email.from:NORA <onboarding@resend.dev>}") String from) {
        this.http =
                WebClient.builder()
                        .baseUrl(RESEND_URL)
                        .defaultHeader("Authorization", "Bearer " + apiKey)
                        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .build();
        this.fromAddress = from;
    }

    @Override
    public void sendEmailVerification(String toEmail, String displayName, String verificationLink) {
        String btnStyle =
                "display:inline-block;padding:10px 20px;background:#111;"
                        + "color:#fff;text-decoration:none;border-radius:6px;";
        send(
                toEmail,
                "Confirme seu e-mail na NORA",
                """
                <p>Ola %s,</p>
                <p>Para ativar sua conta NORA, confirme seu e-mail clicando no botao abaixo.</p>
                <p><a href="%s" style="%s">Confirmar e-mail</a></p>
                <p>Se voce nao criou esta conta, pode ignorar este e-mail.</p>
                """
                        .formatted(escape(displayName), verificationLink, btnStyle));
    }

    @Override
    public void sendPasswordReset(String toEmail, String displayName, String resetLink) {
        String btnStyle =
                "display:inline-block;padding:10px 20px;background:#111;"
                        + "color:#fff;text-decoration:none;border-radius:6px;";
        send(
                toEmail,
                "Redefinicao de senha NORA",
                """
                <p>Ola %s,</p>
                <p>Recebemos um pedido para redefinir sua senha.</p>
                <p><a href="%s" style="%s">Redefinir senha</a></p>
                <p>Este link expira em uma hora. Se voce nao pediu isso, ignore o e-mail.</p>
                """
                        .formatted(escape(displayName), resetLink, btnStyle));
    }

    @Override
    public void sendSignupAttemptOnExistingAccount(
            String toEmail, String displayName, String signInUrl) {
        String btnStyle =
                "display:inline-block;padding:10px 20px;background:#111;"
                        + "color:#fff;text-decoration:none;border-radius:6px;";
        send(
                toEmail,
                "Tentativa de cadastro na NORA",
                """
                <p>Ola %s,</p>
                <p>Alguem tentou criar uma conta NORA com este e-mail, que ja tem uma conta.</p>
                <p>Nada mudou: sua conta e sua senha continuam como estavam.</p>
                <p><a href="%s" style="%s">Entrar na NORA</a></p>
                <p>Se foi voce, e so entrar. Se nao lembra da senha, use "esqueci minha senha"
                na tela de login.</p>
                """
                        .formatted(escape(displayName), signInUrl, btnStyle));
    }

    @Override
    public void sendInvitation(
            String toEmail,
            String tenantName,
            String invitedByName,
            String acceptUrl,
            int expiresInDays) {
        String body =
                br.com.nora.api.application.iam.InviteEmailTemplate.render(
                        tenantName, invitedByName, toEmail, acceptUrl, expiresInDays);
        send(toEmail, "Convite NORA - " + tenantName, body);
    }

    @Override
    public void sendWorkflowNotification(String toEmail, String subject, String htmlBody) {
        // No try/catch on purpose: the WorkflowEngine records the failure in the execution log
        // (status FAILED). Swallowing the error here would show false success in the flow history.
        Map<String, Object> body =
                Map.of("from", fromAddress, "to", toEmail, "subject", subject, "html", htmlBody);
        http.post().bodyValue(body).retrieve().bodyToMono(String.class).block(TIMEOUT);
    }

    private void send(String to, String subject, String html) {
        Map<String, Object> body =
                Map.of("from", fromAddress, "to", to, "subject", subject, "html", html);
        try {
            http.post().bodyValue(body).retrieve().bodyToMono(String.class).block(TIMEOUT);
        } catch (Exception ex) {
            // A send failure must not break the user flow; we log it for investigation.
            LOG.error("Resend send failed to={} subject={}", to, subject, ex);
        }
    }

    private static String escape(String s) {
        return s == null
                ? ""
                : s.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;");
    }
}
