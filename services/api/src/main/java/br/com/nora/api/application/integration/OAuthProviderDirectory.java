package br.com.nora.api.application.integration;

import br.com.nora.api.application.integration.OAuthProviderConfig.TokenAuthStyle;
import br.com.nora.api.application.integration.OAuthProviderConfig.TokenRequestFormat;
import br.com.nora.api.domain.integration.IntegrationProvider;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Catalog of the generic OAuth providers (wave 1: GitHub, Notion, Todoist, Linear; wave 2:
 * Microsoft). URLs, scopes and the quirks of each provider are declared HERE; credentials come from
 * env (never in code). A provider without client-id/secret in the environment simply does not enter
 * the catalog — the hub shows "não configurado" and start returns 422 (fail-visible, same contract
 * as Google/Slack).
 */
@Component
public class OAuthProviderDirectory {

    private final Map<IntegrationProvider, OAuthProviderConfig> configs =
            new EnumMap<>(IntegrationProvider.class);

    public OAuthProviderDirectory(
            @Value("${nora.integrations.github.client-id:}") String githubClientId,
            @Value("${nora.integrations.github.client-secret:}") String githubClientSecret,
            @Value("${nora.integrations.github.redirect-uri:}") String githubRedirectUri,
            @Value("${nora.integrations.notion.client-id:}") String notionClientId,
            @Value("${nora.integrations.notion.client-secret:}") String notionClientSecret,
            @Value("${nora.integrations.notion.redirect-uri:}") String notionRedirectUri,
            @Value("${nora.integrations.todoist.client-id:}") String todoistClientId,
            @Value("${nora.integrations.todoist.client-secret:}") String todoistClientSecret,
            @Value("${nora.integrations.todoist.redirect-uri:}") String todoistRedirectUri,
            @Value("${nora.integrations.linear.client-id:}") String linearClientId,
            @Value("${nora.integrations.linear.client-secret:}") String linearClientSecret,
            @Value("${nora.integrations.linear.redirect-uri:}") String linearRedirectUri,
            @Value("${nora.integrations.microsoft.client-id:}") String microsoftClientId,
            @Value("${nora.integrations.microsoft.client-secret:}") String microsoftClientSecret,
            @Value("${nora.integrations.microsoft.redirect-uri:}") String microsoftRedirectUri) {
        // GitHub: token form-encoded by default — the client sends Accept: application/json.
        // An OAuth App token does not expire; no refresh.
        register(
                IntegrationProvider.GITHUB,
                "https://github.com/login/oauth/authorize",
                "https://github.com/login/oauth/access_token",
                "repo",
                githubClientId,
                githubClientSecret,
                githubRedirectUri,
                TokenAuthStyle.CLIENT_SECRET_BODY,
                TokenRequestFormat.FORM,
                Map.of(),
                null,
                null,
                false);
        // Notion: no scopes (app capabilities), the token endpoint uses HTTP Basic + JSON body,
        // `owner=user` on authorize; the response carries workspace_name (becomes the hub account).
        register(
                IntegrationProvider.NOTION,
                "https://api.notion.com/v1/oauth/authorize",
                "https://api.notion.com/v1/oauth/token",
                "",
                notionClientId,
                notionClientSecret,
                notionRedirectUri,
                TokenAuthStyle.HTTP_BASIC,
                TokenRequestFormat.JSON,
                Map.of("owner", "user"),
                "/workspace_name",
                null,
                false);
        // Todoist: token does not expire; no refresh.
        register(
                IntegrationProvider.TODOIST,
                "https://todoist.com/oauth/authorize",
                "https://todoist.com/oauth/access_token",
                "data:read_write",
                todoistClientId,
                todoistClientSecret,
                todoistRedirectUri,
                TokenAuthStyle.CLIENT_SECRET_BODY,
                TokenRequestFormat.FORM,
                Map.of(),
                null,
                null,
                false);
        // Linear: long-lived token (expires_in ~10 years); `actor=user` on authorize.
        register(
                IntegrationProvider.LINEAR,
                "https://linear.app/oauth/authorize",
                "https://api.linear.app/oauth/token",
                "write",
                linearClientId,
                linearClientSecret,
                linearRedirectUri,
                TokenAuthStyle.CLIENT_SECRET_BODY,
                TokenRequestFormat.FORM,
                Map.of("actor", "user"),
                null,
                null,
                false);
        // Microsoft (Outlook + Calendar, wave 2): `common` tenant (personal or work account), REAL
        // refresh via offline_access (the access token lasts ~1h) and external account read from
        // the id_token `email` claim (openid+email scopes) — the token body does not carry the
        // account.
        register(
                IntegrationProvider.MICROSOFT,
                "https://login.microsoftonline.com/common/oauth2/v2.0/authorize",
                "https://login.microsoftonline.com/common/oauth2/v2.0/token",
                "openid email offline_access Mail.Send Calendars.ReadWrite",
                microsoftClientId,
                microsoftClientSecret,
                microsoftRedirectUri,
                TokenAuthStyle.CLIENT_SECRET_BODY,
                TokenRequestFormat.FORM,
                Map.of(),
                null,
                "email",
                true);
    }

    /** Provider config — empty when the credentials are not in the environment. */
    public Optional<OAuthProviderConfig> find(IntegrationProvider provider) {
        return Optional.ofNullable(configs.get(provider));
    }

    /** Is the generic provider configured in this environment? */
    public boolean configured(IntegrationProvider provider) {
        return configs.containsKey(provider);
    }

    private void register(
            IntegrationProvider provider,
            String authorizationUrl,
            String tokenUrl,
            String scopes,
            String clientId,
            String clientSecret,
            String redirectUri,
            TokenAuthStyle authStyle,
            TokenRequestFormat requestFormat,
            Map<String, String> extraAuthorizeParams,
            String accountJsonPointer,
            String accountIdTokenClaim,
            boolean supportsRefresh) {
        if (isBlank(clientId) || isBlank(clientSecret) || isBlank(redirectUri)) {
            return;
        }
        configs.put(
                provider,
                new OAuthProviderConfig(
                        provider,
                        authorizationUrl,
                        tokenUrl,
                        scopes,
                        clientId,
                        clientSecret,
                        redirectUri,
                        authStyle,
                        requestFormat,
                        extraAuthorizeParams,
                        accountJsonPointer,
                        accountIdTokenClaim,
                        supportsRefresh));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
