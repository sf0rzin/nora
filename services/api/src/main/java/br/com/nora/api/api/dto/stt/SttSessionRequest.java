package br.com.nora.api.api.dto.stt;

/**
 * Body of {@code POST /stt/sessions}. Everything about the session that costs money — provider,
 * model, audio format, VAD — is resolved server-side; the client only says what language it expects
 * to hear.
 *
 * @param language BCP-47 tag as the desktop knows it ({@code pt-BR}). Optional: absent or unusable
 *     falls back to {@code nora.stt.default-language}
 */
public record SttSessionRequest(String language) {}
