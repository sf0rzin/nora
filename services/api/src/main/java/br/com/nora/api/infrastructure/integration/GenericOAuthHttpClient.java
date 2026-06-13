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
 * Adapter HTTP ÚNICO do token exchange dos provedores OAuth genéricos (onda 1: GitHub, Notion,
 * Todoist, Linear; onda 2: Microsoft). A configuração declarativa ({@link OAuthProviderConfig})
 * decide o estilo de autenticação (credenciais no corpo ou HTTP Basic), o formato do corpo (form ou
 * JSON), de onde sai a conta externa (corpo da resposta ou claim do id_token) e se há refresh.
 * Sempre manda {@code Accept: application/json} — o GitHub responde form-encoded sem isso. Erros
 * viram {@code ProviderError} só com o status HTTP (o corpo do provedor pode ecoar dados
 * sensíveis), espelhando Google/Slack.
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
     * Lê a resposta do token endpoint. Alguns provedores (GitHub) respondem HTTP 200 com {@code
     * {"error": "..."}} em code inválido — vira {@code ProviderError} com o código de erro.
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

    /** Conta externa: JSON Pointer no corpo (Notion) ou claim do id_token OIDC (Microsoft). */
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
     * Lê um claim do payload do id_token (JWT OIDC) SEM validar assinatura — o token veio direto do
     * token endpoint via TLS, não do navegador. Fallback em {@code preferred_username} (Microsoft
     * nem sempre emite {@code email} em contas corporativas). Qualquer malformação = null (a
     * conexão não depende da conta exibida no hub).
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

    /** Status HTTP puro em falha de transporte — o corpo pode ecoar dados sensíveis. */
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
