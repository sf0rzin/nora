package br.com.nora.api.application.ports;

/**
 * Porta para envio de e-mails transacionais. Implementacao default no MVP loga no console; em
 * producao um bean Resend e ativado quando RESEND_API_KEY esta presente.
 */
public interface EmailSender {

    void sendEmailVerification(String toEmail, String displayName, String verificationLink);

    void sendPasswordReset(String toEmail, String displayName, String resetLink);
}
