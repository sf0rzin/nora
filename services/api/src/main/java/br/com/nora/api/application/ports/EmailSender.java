package br.com.nora.api.application.ports;

/**
 * Porta para envio de e-mails transacionais. Implementacao default no MVP loga no console; em
 * producao um bean Resend e ativado quando RESEND_API_KEY esta presente.
 */
public interface EmailSender {

    void sendEmailVerification(String toEmail, String displayName, String verificationLink);

    void sendPasswordReset(String toEmail, String displayName, String resetLink);

    /**
     * Envia um convite de usuario (US06). O {@code subject} e os campos sao parametrizados pelo
     * caller, que tambem injeta o link absoluto para o frontend. O adapter e responsavel apenas por
     * o entregar via SMTP/Resend/SDK.
     *
     * @param toEmail destinatario
     * @param tenantName nome amigavel do tenant (renderizado no template)
     * @param invitedByName quem convidou (renderizado no template)
     * @param acceptUrl URL absoluta com token para o frontend
     * @param expiresInDays quantos dias o convite e valido (renderizado no template)
     */
    void sendInvitation(
            String toEmail,
            String tenantName,
            String invitedByName,
            String acceptUrl,
            int expiresInDays);

    /**
     * Envia uma notificação de workflow do NORA Flows (assunto + corpo HTML prontos, montados pela
     * ação). Diferente dos e-mails transacionais acima, FALHA DEVE PROPAGAR (exceção): o engine
     * captura e registra o erro no log da execução — fingir sucesso quebraria o histórico real.
     */
    void sendWorkflowNotification(String toEmail, String subject, String htmlBody);
}
