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

/** Parsing of the generic token endpoint responses (GitHub/Notion/Todoist/Linear). */
class GenericOAuthHttpClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void github_tokenWithNoExpiry() throws Exception {
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
    void notion_workspaceNameBecomesExternalAccount() throws Exception {
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
    void linear_expiresInComesAsLong() throws Exception {
        TokenResponse parsed =
                GenericOAuthHttpClient.parse(
                        mapper.readTree(
                                "{\"access_token\":\"lin_abc\",\"scope\":\"write\","
                                        + "\"expires_in\":315360000}"),
                        config(IntegrationProvider.LINEAR, null));
        assertThat(parsed.expiresInSeconds()).isEqualTo(315360000L);
    }

    /** GitHub answers HTTP 200 with {"error": ...} on an invalid code — becomes ProviderError. */
    @Test
    void errorWithHttp200_becomesProviderErrorWithCode() throws Exception {
        assertThatThrownBy(
                        () ->
                                GenericOAuthHttpClient.parse(
                                        mapper.readTree("{\"error\":\"bad_verification_code\"}"),
                                        config(IntegrationProvider.GITHUB, null)))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("bad_verification_code");
    }

    /** Microsoft: refresh_token persists and the account comes from the id_token `email` claim. */
    @Test
    void microsoft_refreshTokenAndAccountFromIdToken() throws Exception {
        String idToken = jwt("{\"email\":\"conta@outlook.com\",\"aud\":\"x\"}");
        TokenResponse parsed =
                GenericOAuthHttpClient.parse(
                        mapper.readTree(
                                "{\"access_token\":\"ms_at\",\"refresh_token\":\"ms_rt\","
                                        + "\"expires_in\":3599,\"scope\":\"Mail.Send\","
                                        + "\"id_token\":\""
                                        + idToken
                                        + "\"}"),
                        config(IntegrationProvider.MICROSOFT, null, "email"));
        assertThat(parsed.accessToken()).isEqualTo("ms_at");
        assertThat(parsed.refreshToken()).isEqualTo("ms_rt");
        assertThat(parsed.expiresInSeconds()).isEqualTo(3599L);
        assertThat(parsed.externalAccount()).isEqualTo("conta@outlook.com");
    }

    /** Corporate account with no `email` claim — falls back to preferred_username. */
    @Test
    void microsoft_noEmailFallsBackToPreferredUsername() throws Exception {
        String idToken = jwt("{\"preferred_username\":\"ana@empresa.com\"}");
        TokenResponse parsed =
                GenericOAuthHttpClient.parse(
                        mapper.readTree(
                                "{\"access_token\":\"ms_at\",\"id_token\":\"" + idToken + "\"}"),
                        config(IntegrationProvider.MICROSOFT, null, "email"));
        assertThat(parsed.externalAccount()).isEqualTo("ana@empresa.com");
    }

    /** A malformed id_token does not take down the connection — it just shows no account. */
    @Test
    void microsoft_malformedIdToken_doesNotBreak() throws Exception {
        TokenResponse parsed =
                GenericOAuthHttpClient.parse(
                        mapper.readTree("{\"access_token\":\"ms_at\",\"id_token\":\"lixo\"}"),
                        config(IntegrationProvider.MICROSOFT, null, "email"));
        assertThat(parsed.accessToken()).isEqualTo("ms_at");
        assertThat(parsed.externalAccount()).isNull();
    }

    /** Wave 1 still has no refresh: missing field becomes null (no empty string). */
    @Test
    void wave1_withNoRefreshToken_staysNull() throws Exception {
        TokenResponse parsed =
                GenericOAuthHttpClient.parse(
                        mapper.readTree("{\"access_token\":\"gho_abc\"}"),
                        config(IntegrationProvider.GITHUB, null));
        assertThat(parsed.refreshToken()).isNull();
    }

    @Test
    void responseWithoutAccessToken_failsClearly() throws Exception {
        assertThatThrownBy(
                        () ->
                                GenericOAuthHttpClient.parse(
                                        mapper.readTree("{}"),
                                        config(IntegrationProvider.TODOIST, null)))
                .isInstanceOf(IntegrationException.ProviderError.class)
                .hasMessageContaining("no access_token");
    }

    /** Test JWT: fake header and signature, real payload in base64url. */
    private static String jwt(String payloadJson) {
        java.util.Base64.Encoder b64 = java.util.Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString(
                        "{\"alg\":\"none\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + "."
                + b64.encodeToString(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                + ".signature";
    }

    private static OAuthProviderConfig config(IntegrationProvider provider, String accountPointer) {
        return config(provider, accountPointer, null);
    }

    private static OAuthProviderConfig config(
            IntegrationProvider provider, String accountPointer, String idTokenClaim) {
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
                accountPointer,
                idTokenClaim,
                idTokenClaim != null);
    }
}
