package br.com.nora.api.application.ports;

import br.com.nora.api.application.integration.OAuthProviderConfig;

/**
 * Port for the GENERIC OAuth 2.0 code-flow (wave 1: GitHub, Notion, Todoist, Linear; wave 2:
 * Microsoft). The provider configuration ({@link OAuthProviderConfig}) describes URLs,
 * authentication style and parsing — the infrastructure implementation is a single HTTP client; in
 * tests it is stubbed.
 *
 * <p>Providers with {@code supportsRefresh} (Microsoft) issue a refresh token on the code exchange
 * and renew via {@link #refresh}; the others store only the access token (long-lived) and {@code
 * expiresAt} when the provider reports {@code expires_in}.
 */
public interface GenericOAuthClient {

    /**
     * Exchanges the authorization code for the access token according to the provider config.
     *
     * @param config provider + credentials + token endpoint style
     * @param code authorization code received in the callback
     */
    TokenResponse exchangeCode(OAuthProviderConfig config, String code);

    /**
     * Renews the access token with the refresh token (grant {@code refresh_token}). Only makes
     * sense for providers with {@code supportsRefresh} — the {@code IntegrationService} is the one
     * that decides when to call it (60s skew, same semantics as Google).
     */
    TokenResponse refresh(OAuthProviderConfig config, String refreshToken);

    /**
     * @param refreshToken issued only by providers with {@code supportsRefresh} (Microsoft); null
     *     for the others. On refresh, null = the provider did not rotate it (keeps the current one)
     * @param externalAccount account/workspace identified in the token response (e.g. the Notion
     *     workspace, the e-mail from the Microsoft id_token); null when the provider does not
     *     expose it
     * @param expiresInSeconds validity in seconds when the provider reports it; null = never
     *     expires
     */
    record TokenResponse(
            String accessToken,
            String refreshToken,
            String scope,
            String externalAccount,
            Long expiresInSeconds) {}
}
