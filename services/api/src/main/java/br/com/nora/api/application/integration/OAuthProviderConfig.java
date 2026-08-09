package br.com.nora.api.application.integration;

import br.com.nora.api.domain.integration.IntegrationProvider;
import java.util.Map;

/**
 * Declarative configuration of a standard OAuth 2.0 code-flow provider (wave 1: GitHub, Notion,
 * Todoist, Linear; wave 2: Microsoft). Adding a new provider = declaring this record in {@link
 * OAuthProviderDirectory} — {@code GenericOAuthHttpClient} and {@code IntegrationService} cover the
 * rest (authorize URL, code exchange, optional refresh, encrypted persistence).
 *
 * <p>Google and Slack do NOT go through here: Google has a historical dedicated client and Slack
 * has its own {@code ok/error} envelope — both keep their dedicated clients (ADR 0031).
 *
 * @param scopes scope list in the provider's format; empty when access comes from the app's
 *     capabilities (Notion)
 * @param tokenAuthStyle how the token endpoint authenticates the app (credentials in the body or
 *     HTTP Basic)
 * @param tokenRequestFormat format of the POST body at the token endpoint (form-urlencoded or JSON)
 * @param extraAuthorizeParams fixed extra parameters of the authorization URL (e.g. {@code
 *     owner=user} on Notion, {@code actor=user} on Linear)
 * @param accountJsonPointer JSON Pointer into the token response body holding the name of the
 *     external account shown in the hub (e.g. {@code /workspace_name} on Notion); null = no
 *     identifiable account
 * @param accountIdTokenClaim claim of the {@code id_token} (OIDC JWT) holding the external account
 *     when it does not come in the response body (e.g. {@code email} on Microsoft); requires the
 *     {@code openid email} scopes; null = does not use id_token
 * @param supportsRefresh does the provider issue a refresh token (e.g. Microsoft with {@code
 *     offline_access})? When true, {@code IntegrationService} renews the expired access token with
 *     the same semantics as Google (60s skew + persisted rotation); when false, an expired token
 *     tells the user to reconnect
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
        String accountJsonPointer,
        String accountIdTokenClaim,
        boolean supportsRefresh) {

    /** How the app authenticates at the token endpoint. */
    public enum TokenAuthStyle {
        /** client_id/client_secret in the request body (GitHub, Todoist, Linear, Microsoft). */
        CLIENT_SECRET_BODY,
        /** HTTP Basic with {@code base64(clientId:clientSecret)} (Notion). */
        HTTP_BASIC
    }

    /** Format of the POST body at the token endpoint. */
    public enum TokenRequestFormat {
        /** application/x-www-form-urlencoded (GitHub, Todoist, Linear, Microsoft). */
        FORM,
        /** application/json (Notion). */
        JSON
    }
}
