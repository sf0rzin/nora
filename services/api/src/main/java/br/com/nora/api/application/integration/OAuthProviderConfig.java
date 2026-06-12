package br.com.nora.api.application.integration;

import br.com.nora.api.domain.integration.IntegrationProvider;
import java.util.Map;

/**
 * Configuração declarativa de um provedor OAuth 2.0 code-flow padrão (onda 1: GitHub, Notion,
 * Todoist, Linear). Adicionar um provedor novo = declarar este record no {@link
 * OAuthProviderDirectory} — o {@code GenericOAuthHttpClient} e o {@code IntegrationService} cobrem
 * o resto (authorize URL, troca do code, persistência cifrada).
 *
 * <p>Google e Slack NÃO passam por aqui: Google tem refresh com rotação/skew e Slack tem o envelope
 * {@code ok/error} próprio — ambos mantêm seus clients dedicados (ADR 0031).
 *
 * @param scopes lista de escopos no formato do provedor; vazio quando os acessos vêm das
 *     capabilities do app (Notion)
 * @param tokenAuthStyle como o token endpoint autentica o app (credenciais no corpo ou HTTP Basic)
 * @param tokenRequestFormat formato do corpo do POST no token endpoint (form-urlencoded ou JSON)
 * @param extraAuthorizeParams parâmetros extras fixos da URL de autorização (ex.: {@code
 *     owner=user} no Notion, {@code actor=user} no Linear)
 * @param accountJsonPointer JSON Pointer no corpo da resposta do token com o nome da conta externa
 *     exibida no hub (ex.: {@code /workspace_name} no Notion); nulo = sem conta identificável
 */
public record OAuthProviderConfig(
        IntegrationProvider provider,
        String authorizationUrl,
        String tokenUrl,
        String scopes,
        String clientId,
        String clientSecret,
        String redirectUri,
        TokenAuthStyle tokenAuthStyle,
        TokenRequestFormat tokenRequestFormat,
        Map<String, String> extraAuthorizeParams,
        String accountJsonPointer) {

    /** Autenticação do app no token endpoint. */
    public enum TokenAuthStyle {
        /** client_id/client_secret no corpo da requisição (GitHub, Todoist, Linear). */
        CLIENT_SECRET_BODY,
        /** HTTP Basic com {@code base64(clientId:clientSecret)} (Notion). */
        HTTP_BASIC
    }

    /** Formato do corpo do POST no token endpoint. */
    public enum TokenRequestFormat {
        /** application/x-www-form-urlencoded (GitHub, Todoist, Linear). */
        FORM,
        /** application/json (Notion). */
        JSON
    }
}
