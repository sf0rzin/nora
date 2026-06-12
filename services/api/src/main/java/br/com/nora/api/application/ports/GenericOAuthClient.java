package br.com.nora.api.application.ports;

import br.com.nora.api.application.integration.OAuthProviderConfig;

/**
 * Porta do fluxo OAuth 2.0 code-flow GENÉRICO (onda 1: GitHub, Notion, Todoist, Linear). A
 * configuração do provedor ({@link OAuthProviderConfig}) descreve URLs, estilo de autenticação e
 * parse — a implementação infrastructure é um client HTTP único; nos testes é stubada.
 *
 * <p>Nenhum provedor da onda 1 emite refresh token na prática — a conexão guarda só o access token
 * (longa duração) e {@code expiresAt} quando o provedor informa {@code expires_in}.
 */
public interface GenericOAuthClient {

    /**
     * Troca o authorization code pelo access token conforme a configuração do provedor.
     *
     * @param config provedor + credenciais + estilo do token endpoint
     * @param code authorization code recebido no callback
     */
    TokenResponse exchangeCode(OAuthProviderConfig config, String code);

    /**
     * @param externalAccount conta/workspace identificada na resposta do token (ex.: workspace do
     *     Notion); nula quando o provedor não a expõe nessa resposta
     * @param expiresInSeconds validade em segundos quando o provedor informa; nula = não expira
     */
    record TokenResponse(
            String accessToken, String scope, String externalAccount, Long expiresInSeconds) {}
}
