package br.com.nora.api.infrastructure.integration;

import br.com.nora.api.application.integration.IntegrationException;
import br.com.nora.api.application.integration.OAuthProviderConfig;
import br.com.nora.api.application.integration.OAuthProviderConfig.TokenAuthStyle;
import br.com.nora.api.application.integration.OAuthProviderConfig.TokenRequestFormat;
import br.com.nora.api.application.ports.GenericOAuthClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * SINGLE HTTP adapter for the token exchange of the generic OAuth providers (wave 1: GitHub,
 * Notion, Todoist, Linear; wave 2: Microsoft). The declarative configuration ({@link
 * OAuthProviderConfig}) decides the authentication style (credentials in the body or HTTP Basic),
 * the body format (form or JSON), where the external account comes from (response body or id_token
 * claim) and whether there is refresh. Always sends {@code Accept: application/json} — GitHub
 * responds form-encoded without it. Errors become {@code ProviderError} with only the HTTP status
 * (the provider body may echo sensitive data), mirroring Google/Slack.
 */
@Component
public class GenericOAuthHttpClient implements GenericOAuthClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient http;
    private final ObjectMapper mapper;

    public GenericOAuthHttpClient(ObjectMapper mapper) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
    }

    @Override
    public TokenResponse exchangeCode(OAuthProviderConfig config, String code) {
        return tokenCall(config, "token", request -> withCodeBody(request, config, code));
    }

    @Override
    public TokenResponse refresh(OAuthProviderConfig config, String refreshToken) {
        return tokenCall(
                config, "refresh", request -> withRefreshBody(request, config, refreshToken));
    }

    private TokenResponse tokenCall(
            OAuthProviderConfig config, String label, BodyAttacher attachBody) {
        String provider = config.provider().wire();
        try {
            WebClient.RequestBodySpec request =
                    http.post().uri(config.tokenUrl()).accept(MediaType.APPLICATION_JSON);
            if (config.tokenAuthStyle() == TokenAuthStyle.HTTP_BASIC) {
                request = request.header("Authorization", basicAuth(config));
            }
            String body =
                    attachBody.attach(request).retrieve().bodyToMono(String.class).block(TIMEOUT);
            return parse(mapper.readTree(body == null ? "{}" : body), config);
        } catch (IntegrationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IntegrationException.ProviderError(provider, label + ": " + reason(ex));
        }
    }

    /**
     * Reads the token endpoint response. Some providers (GitHub) respond HTTP 200 with {@code
     * {"error": "..."}} on an invalid code — becomes {@code ProviderError} with the error code.
     */
    static TokenResponse parse(JsonNode json, OAuthProviderConfig config) {
        String accessToken = json.path("access_token").asText(null);
        if (accessToken == null || accessToken.isBlank()) {
            String error = json.path("error").asText("resposta sem access_token");
            throw new IntegrationException.ProviderError(
                    config.provider().wire(), "token: " + error);
        }
        String refreshToken = json.path("refresh_token").asText(null);
        String externalAccount = externalAccount(json, config);
        Long expiresIn =
                json.path("expires_in").isNumber() ? json.path("expires_in").asLong() : null;
        return new TokenResponse(
                accessToken,
                refreshToken == null || refreshToken.isBlank() ? null : refreshToken,
                json.path("scope").asText(null),
                externalAccount,
                expiresIn);
    }

    /** External account: JSON Pointer in the body (Notion) or OIDC id_token claim (Microsoft). */
    private static String externalAccount(JsonNode json, OAuthProviderConfig config) {
        if (config.accountJsonPointer() != null) {
            return textAt(json, config.accountJsonPointer());
        }
        if (config.accountIdTokenClaim() != null) {
            return idTokenClaim(json.path("id_token").asText(null), config.accountIdTokenClaim());
        }
        return null;
    }

    /**
     * Reads a claim from the id_token payload (OIDC JWT) WITHOUT validating the signature — the
     * token came straight from the token endpoint over TLS, not from the browser. Falls back to
     * {@code preferred_username} (Microsoft does not always emit {@code email} for corporate
     * accounts). Any malformation = null (the connection does not depend on the account shown in
     * the hub).
     */
    static String idTokenClaim(String idToken, String claim) {
        if (idToken == null || idToken.isBlank()) {
            return null;
        }
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            JsonNode payload =
                    new ObjectMapper()
                            .readTree(
                                    new String(
                                            Base64.getUrlDecoder().decode(parts[1]),
                                            StandardCharsets.UTF_8));
            String value = payload.path(claim).asText(null);
            if (value == null || value.isBlank()) {
                value = payload.path("preferred_username").asText(null);
            }
            return value == null || value.isBlank() ? null : value;
        } catch (Exception ex) {
            return null;
        }
    }

    private WebClient.RequestHeadersSpec<?> withCodeBody(
            WebClient.RequestBodySpec request, OAuthProviderConfig config, String code) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "authorization_code");
        fields.put("code", code);
        fields.put("redirect_uri", config.redirectUri());
        return withBody(request, config, fields);
    }

    private WebClient.RequestHeadersSpec<?> withRefreshBody(
            WebClient.RequestBodySpec request, OAuthProviderConfig config, String refreshToken) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("grant_type", "refresh_token");
        fields.put("refresh_token", refreshToken);
        return withBody(request, config, fields);
    }

    private WebClient.RequestHeadersSpec<?> withBody(
            WebClient.RequestBodySpec request,
            OAuthProviderConfig config,
            Map<String, String> fields) {
        boolean credentialsInBody = config.tokenAuthStyle() == TokenAuthStyle.CLIENT_SECRET_BODY;
        if (config.tokenRequestFormat() == TokenRequestFormat.JSON) {
            Map<String, String> payload = new LinkedHashMap<>(fields);
            if (credentialsInBody) {
                payload.put("client_id", config.clientId());
                payload.put("client_secret", config.clientSecret());
            }
            return request.contentType(MediaType.APPLICATION_JSON).bodyValue(payload);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        fields.forEach(form::add);
        if (credentialsInBody) {
            form.add("client_id", config.clientId());
            form.add("client_secret", config.clientSecret());
        }
        return request.contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form));
    }

    private static String basicAuth(OAuthProviderConfig config) {
        String raw = config.clientId() + ":" + config.clientSecret();
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String textAt(JsonNode json, String pointer) {
        JsonNode node = json.at(pointer);
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    /** Pure HTTP status on transport failure — the body may echo sensitive data. */
    private static String reason(Exception ex) {
        if (ex instanceof WebClientResponseException http) {
            return String.valueOf(http.getStatusCode().value());
        }
        return ex.getMessage();
    }

    @FunctionalInterface
    private interface BodyAttacher {
        WebClient.RequestHeadersSpec<?> attach(WebClient.RequestBodySpec request);
    }
}
