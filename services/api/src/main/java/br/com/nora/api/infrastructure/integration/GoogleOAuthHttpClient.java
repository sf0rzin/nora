package br.com.nora.api.infrastructure.integration;

import br.com.nora.api.application.integration.IntegrationException;
import br.com.nora.api.application.ports.GoogleOAuthClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Google OAuth HTTP adapter (token endpoint + userinfo). No SDK: two POSTs and one GET — fewer
 * dependencies, more visibility. Errors become {@code IntegrationException.ProviderError} with
 * Google's error code (without leaking tokens in the log).
 */
@Component
public class GoogleOAuthHttpClient implements GoogleOAuthClient {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleOAuthHttpClient.class);
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URL = "https://openidconnect.googleapis.com/v1/userinfo";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient http;
    private final ObjectMapper mapper;
    private final String clientId;
    private final String clientSecret;

    public GoogleOAuthHttpClient(
            ObjectMapper mapper,
            @Value("${nora.integrations.google.client-id:}") String clientId,
            @Value("${nora.integrations.google.client-secret:}") String clientSecret) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public TokenResponse exchangeCode(String code, String redirectUri) {
        return tokenCall(
                BodyInserters.fromFormData("grant_type", "authorization_code")
                        .with("code", code)
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret)
                        .with("redirect_uri", redirectUri));
    }

    @Override
    public TokenResponse refresh(String refreshToken) {
        return tokenCall(
                BodyInserters.fromFormData("grant_type", "refresh_token")
                        .with("refresh_token", refreshToken)
                        .with("client_id", clientId)
                        .with("client_secret", clientSecret));
    }

    @Override
    public String userEmail(String accessToken) {
        try {
            String body =
                    http.get()
                            .uri(USERINFO_URL)
                            .header("Authorization", "Bearer " + accessToken)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            return json.path("email").isTextual() ? json.path("email").asText() : null;
        } catch (Exception ex) {
            throw new IntegrationException.ProviderError("google", "userinfo: " + reason(ex));
        }
    }

    private TokenResponse tokenCall(BodyInserters.FormInserter<String> form) {
        try {
            String body =
                    http.post()
                            .uri(TOKEN_URL)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(form)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            String accessToken = json.path("access_token").asText(null);
            if (accessToken == null) {
                throw new IntegrationException.ProviderError("google", "resposta sem access_token");
            }
            return new TokenResponse(
                    accessToken,
                    json.path("refresh_token").asText(null),
                    json.path("expires_in").asLong(3600),
                    json.path("scope").asText(null));
        } catch (IntegrationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IntegrationException.ProviderError("google", "token: " + reason(ex));
        }
    }

    /** Extracts Google's error code without echoing the whole body (may contain sensitive data). */
    private String reason(Exception ex) {
        if (ex instanceof WebClientResponseException http) {
            String body = http.getResponseBodyAsString();
            try {
                JsonNode json = mapper.readTree(body);
                String error = json.path("error").asText("");
                String desc = json.path("error_description").asText("");
                if (!error.isBlank()) {
                    return http.getStatusCode().value()
                            + " "
                            + error
                            + (desc.isBlank() ? "" : " (" + desc + ")");
                }
            } catch (Exception ignored) {
                // non-JSON response — falls through to the pure status below
            }
            return String.valueOf(http.getStatusCode().value());
        }
        return ex.getMessage();
    }
}
