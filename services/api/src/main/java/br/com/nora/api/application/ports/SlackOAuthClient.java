package br.com.nora.api.application.ports;

/**
 * Port for Slack's OAuth v2 flow (authorization code → bot token). Unlike Google, the bot token
 * ({@code xoxb-...}) does NOT expire — there is no refresh. The infrastructure implementation talks
 * to slack.com/api; in tests it is stubbed.
 */
public interface SlackOAuthClient {

    /**
     * Exchanges the authorization code for the workspace's bot token.
     *
     * @param code authorization code received in the callback
     * @param redirectUri MUST be identical to the one used in the authorization URL
     */
    TokenResponse exchangeCode(String code, String redirectUri);

    /**
     * @param botToken bot token ({@code xoxb-...}); does not expire, no refresh
     * @param teamName name of the connected workspace (becomes the "external account" in the hub)
     * @param scope scopes granted to the bot (e.g. {@code chat:write,channels:read})
     */
    record TokenResponse(String botToken, String teamName, String scope) {}
}
