package br.com.nora.api.infrastructure.email;

import br.com.nora.api.application.ports.EmailSender;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Adapter para a Resend API (https://resend.com/docs/api-reference/emails/send-email).
 *
 * <p>Ativado quando {@code RESEND_API_KEY} esta presente OU {@code nora.email.provider=resend}.
 * Caso contrario o {@link LogEmailSender} permanece como default e nada de externo e chamado.
 *
 * <p>Free tier (3.000 e-mails/mes) cobre o MVP inteiro. Custos reais: zero ate o lancamento.
 */
@Component(value = "resendEmailSender")
@ConditionalOnProperty(name = "nora.email.resend.api-key")
public class ResendEmailSender implements EmailSender {

    private static final Logger LOG = LoggerFactory.getLogger(ResendEmailSender.class);
    private static final String RESEND_URL = "https://api.resend.com/emails";

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
    public void sendEmailVerification(
            String toEmail, String displayName, String verificationLink) {
        send(
                toEmail,
                "Confirme seu e-mail na NORA",
                """
                <p>Ola %s,</p>
                <p>Para ativar sua conta NORA, confirme seu e-mail clicando no botao abaixo.</p>
                <p><a href="%s" style="display:inline-block;padding:10px 20px;background:#111;color:#fff;text-decoration:none;border-radius:6px;">Confirmar e-mail</a></p>
                <p>Se voce nao criou esta conta, pode ignorar este e-mail.</p>
                """
                        .formatted(escape(displayName), verificationLink));
    }

    @Override
    public void sendPasswordReset(String toEmail, String displayName, String resetLink) {
        send(
                toEmail,
                "Redefinicao de senha NORA",
                """
                <p>Ola %s,</p>
                <p>Recebemos um pedido para redefinir sua senha.</p>
                <p><a href="%s" style="display:inline-block;padding:10px 20px;background:#111;color:#fff;text-decoration:none;border-radius:6px;">Redefinir senha</a></p>
                <p>Este link expira em uma hora. Se voce nao pediu isso, ignore o e-mail.</p>
                """
                        .formatted(escape(displayName), resetLink));
    }

    private void send(String to, String subject, String html) {
        Map<String, Object> body =
                Map.of("from", fromAddress, "to", to, "subject", subject, "html", html);
        try {
            http.post()
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(10));
        } catch (Exception ex) {
            // Falha de envio nao deve quebrar o fluxo do usuario; logamos para investigar.
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
