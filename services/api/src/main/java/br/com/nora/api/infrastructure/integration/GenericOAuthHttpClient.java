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
 * Todoist, Linear). A configuração declarativa ({@link OAuthProviderConfig}) decide o estilo de
 * autenticação (credenciais no corpo ou HTTP Basic), o formato do corpo (form ou JSON) e de onde
 * sai a conta externa. Sempre manda {@code Accept: application/json} — o GitHub responde
 * form-encoded sem isso. Erros viram {@code ProviderError} só com o status HTTP (o corpo do
 * provedor pode ecoar dados sensíveis), espelhando Google/Slack.
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
        String provider = config.provider().wire();
        try {
            WebClient.RequestBodySpec request =
                    http.post().uri(config.tokenUrl()).accept(MediaType.APPLICATION_JSON);
            if (config.tokenAuthStyle() == TokenAuthStyle.HTTP_BASIC) {
                request = request.header("Authorization", basicAuth(config));
            }
            String body =
                    withBody(request, config, code)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            return parse(mapper.readTree(body == null ? "{}" : body), config);
        } catch (IntegrationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IntegrationException.ProviderError(provider, "token: " + reason(ex));
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
        String externalAccount =
                config.accountJsonPointer() == null
                        ? null
                        : textAt(json, config.accountJsonPointer());
        Long expiresIn =
                json.path("expires_in").isNumber() ? json.path("expires_in").asLong() : null;
        return new TokenResponse(
                accessToken, json.path("scope").asText(null), externalAccount, expiresIn);
    }

    private WebClient.RequestHeadersSpec<?> withBody(
            WebClient.RequestBodySpec request, OAuthProviderConfig config, String code) {
        boolean credentialsInBody = config.tokenAuthStyle() == TokenAuthStyle.CLIENT_SECRET_BODY;
        if (config.tokenRequestFormat() == TokenRequestFormat.JSON) {
            Map<String, String> payload = new LinkedHashMap<>();
            payload.put("grant_type", "authorization_code");
            payload.put("code", code);
            payload.put("redirect_uri", config.redirectUri());
            if (credentialsInBody) {
                payload.put("client_id", config.clientId());
                payload.put("client_secret", config.clientSecret());
            }
            return request.contentType(MediaType.APPLICATION_JSON).bodyValue(payload);
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", config.redirectUri());
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
}
