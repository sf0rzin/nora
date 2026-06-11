package br.com.nora.api.application.ports;

/**
 * Porta do fluxo OAuth v2 do Slack (authorization code → bot token). Diferente do Google, o bot
 * token ({@code xoxb-...}) NÃO expira — não existe refresh. A implementação infrastructure fala com
 * slack.com/api; nos testes é stubada.
 */
public interface SlackOAuthClient {

    /**
     * Troca o authorization code pelo bot token da workspace.
     *
     * @param code authorization code recebido no callback
     * @param redirectUri DEVE ser idêntico ao usado na URL de autorização
     */
    TokenResponse exchangeCode(String code, String redirectUri);

    /**
     * @param botToken token do bot ({@code xoxb-...}); não expira, sem refresh
     * @param teamName nome da workspace conectada (vira a "conta externa" no hub)
     * @param scope escopos concedidos ao bot (ex.: {@code chat:write,channels:read})
     */
    record TokenResponse(String botToken, String teamName, String scope) {}
}
