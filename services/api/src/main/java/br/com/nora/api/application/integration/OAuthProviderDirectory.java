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
 * Catálogo dos provedores OAuth genéricos (onda 1: GitHub, Notion, Todoist, Linear; onda 2:
 * Microsoft). URLs, escopos e particularidades de cada provedor ficam declarados AQUI; credenciais
 * vêm de env (nunca em código). Provedor sem client-id/secret no ambiente simplesmente não entra no
 * catálogo — o hub mostra "não configurado" e o start retorna 422 (fail-visible, mesmo contrato do
 * Google/Slack).
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
        // GitHub: token form-encoded por default — o client manda Accept: application/json.
        // Token de OAuth App não expira; sem refresh.
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
        // Notion: sem scopes (capabilities do app), token endpoint usa HTTP Basic + corpo JSON,
        // `owner=user` no authorize; a resposta traz workspace_name (vira a conta no hub).
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
        // Todoist: token não expira; sem refresh.
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
        // Linear: token de longa duração (expires_in ~10 anos); `actor=user` no authorize.
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
        // Microsoft (Outlook + Calendar, onda 2): tenant `common` (conta pessoal ou corporativa),
        // refresh REAL via offline_access (access token dura ~1h) e conta externa lida do claim
        // `email` do id_token (escopos openid+email) — o corpo do token nao traz a conta.
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

    /** Config do provedor — vazio quando as credenciais não estão no ambiente. */
    public Optional<OAuthProviderConfig> find(IntegrationProvider provider) {
        return Optional.ofNullable(configs.get(provider));
    }

    /** O provedor genérico está configurado neste ambiente? */
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
