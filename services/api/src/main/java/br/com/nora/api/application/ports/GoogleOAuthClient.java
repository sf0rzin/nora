package br.com.nora.api.application.ports;

/**
 * Porta do fluxo OAuth 2.0 com o Google (authorization code + refresh). A implementação
 * infrastructure fala com accounts.google.com/oauth2.googleapis.com; nos testes é stubada.
 */
public interface GoogleOAuthClient {

    /**
     * Troca o authorization code por tokens.
     *
     * @param code authorization code recebido no callback
     * @param redirectUri DEVE ser idêntico ao usado na URL de autorização
     */
    TokenResponse exchangeCode(String code, String redirectUri);

    /**
     * Renova o access token. O Google normalmente NÃO devolve refresh_token novo (mantém o atual).
     */
    TokenResponse refresh(String refreshToken);

    /** E-mail da conta Google conectada (scope openid email). */
    String userEmail(String accessToken);

    /**
     * @param refreshToken pode ser nulo no refresh (Google não rotaciona por padrão)
     * @param expiresInSeconds validade do access token a partir de agora
     */
    record TokenResponse(
            String accessToken, String refreshToken, long expiresInSeconds, String scope) {}
}
