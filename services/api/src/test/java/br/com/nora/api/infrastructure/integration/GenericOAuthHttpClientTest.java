package br.com.nora.api.infrastructure.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.integration.IntegrationException;
import br.com.nora.api.application.integration.OAuthProviderConfig;
import br.com.nora.api.application.integration.OAuthProviderConfig.TokenAuthStyle;
import br.com.nora.api.application.integration.OAuthProviderConfig.TokenRequestFormat;
import br.com.nora.api.application.ports.GenericOAuthClient.TokenResponse;
import br.com.nora.api.domain.integration.IntegrationProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Parse das respostas do token endpoint genérico (GitHub/Notion/Todoist/Linear). */
class GenericOAuthHttpClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void github_tokenSemExpiracao() throws Exception {
        TokenResponse parsed =
                GenericOAuthHttpClient.parse(
                        mapper.readTree(
                                "{\"access_token\":\"gho_abc\",\"scope\":\"repo\","
                                        + "\"token_type\":\"bearer\"}"),
                        config(IntegrationProvider.GITHUB, null));
        assertThat(parsed.accessToken()).isEqualTo("gho_abc");
        assertThat(parsed.scope()).isEqualTo("repo");
        assertThat(parsed.externalAccount()).isNull();
        assertThat(parsed.expiresInSeconds()).isNull();
    }

    @Test
    void notion_workspaceNameViraContaExterna() throws Exception {
        TokenResponse parsed =
                GenericOAuthHttpClient.parse(
                        mapper.readTree(
                                "{\"access_token\":\"ntn_abc\",\"workspace_name\":\"NORA HQ\","
                                        + "\"workspace_id\":\"ws-1\",\"owner\":{}}"),
                        config(IntegrationProvider.NOTION, "/workspace_name"));
        assertThat(parsed.accessToken()).isEqualTo("ntn_abc");
        assertThat(parsed.externalAccount()).isEqualTo("NORA HQ");
    }

    @Test
    void linear_expiresInVemComoLong() throws Exception {
        TokenResponse parsed =
                GenericOAuthHttpClient.parse(
                        mapper.readTree(
                                "{\"access_token\":\"lin_abc\",\"scope\":\"write\","
                                        + "\"expires_in\":315360000}"),
                        config(IntegrationProvider.LINEAR, null));
        assertThat(parsed.expiresInSeconds()).isEqualTo(315360000L);
    }

    /** GitHub responde HTTP 200 com {"error": ...} em code inválido — vira ProviderError. */
    @Test
    void erroComHttp200_viraProviderErrorComCodigo() throws Exception {
        assertThatThrownBy(
                        () ->
                                GenericOAuthHttpClient.parse(
                                        mapper.readTree("{\"error\":\"bad_verification_code\"}"),
                                        config(IntegrationProvider.GITHUB, null)))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("bad_verification_code");
    }

    @Test
    void respostaSemAccessToken_falhaClaro() throws Exception {
        assertThatThrownBy(
                        () ->
                                GenericOAuthHttpClient.parse(
                                        mapper.readTree("{}"),
                                        config(IntegrationProvider.TODOIST, null)))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("sem access_token");
    }

    private static OAuthProviderConfig config(IntegrationProvider provider, String accountPointer) {
        return new OAuthProviderConfig(
                provider,
                "https://example.test/authorize",
                "https://example.test/token",
                "scope",
                "client-id",
                "client-secret",
                "http://localhost:8080/integrations/" + provider.wire() + "/oauth/callback",
                TokenAuthStyle.CLIENT_SECRET_BODY,
                TokenRequestFormat.FORM,
                Map.of(),
                accountPointer);
    }
}
