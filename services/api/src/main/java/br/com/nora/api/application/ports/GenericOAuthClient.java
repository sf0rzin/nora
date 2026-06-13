package br.com.nora.api.application.ports;

import br.com.nora.api.application.integration.OAuthProviderConfig;

/**
 * Porta do fluxo OAuth 2.0 code-flow GENÉRICO (onda 1: GitHub, Notion, Todoist, Linear; onda 2:
 * Microsoft). A configuração do provedor ({@link OAuthProviderConfig}) descreve URLs, estilo de
 * autenticação e parse — a implementação infrastructure é um client HTTP único; nos testes é
 * stubada.
 *
 * <p>Provedores com {@code supportsRefresh} (Microsoft) emitem refresh token na troca do code e
 * renovam via {@link #refresh}; os demais guardam só o access token (longa duração) e {@code
 * expiresAt} quando o provedor informa {@code expires_in}.
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
     * Renova o access token com o refresh token (grant {@code refresh_token}). Só faz sentido para
     * provedores com {@code supportsRefresh} — o {@code IntegrationService} é quem decide quando
     * chamar (skew de 60s, mesma semântica do Google).
     */
    TokenResponse refresh(OAuthProviderConfig config, String refreshToken);

    /**
     * @param refreshToken emitido só por provedores com {@code supportsRefresh} (Microsoft); nulo
     *     nos demais. No refresh, nulo = provedor não rotacionou (mantém o atual)
     * @param externalAccount conta/workspace identificada na resposta do token (ex.: workspace do
     *     Notion, e-mail do id_token Microsoft); nula quando o provedor não a expõe
     * @param expiresInSeconds validade em segundos quando o provedor informa; nula = não expira
     */
    record TokenResponse(
            String accessToken,
            String refreshToken,
            String scope,
            String externalAccount,
            Long expiresInSeconds) {}
}
