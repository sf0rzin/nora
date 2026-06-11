package br.com.nora.api.infrastructure.integration;

import br.com.nora.api.application.integration.IntegrationException;
import br.com.nora.api.application.ports.SlackOAuthClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Adapter HTTP do OAuth v2 do Slack ({@code oauth.v2.access}). Sem SDK, espelhando o {@link
 * GoogleOAuthHttpClient}: um POST form e pronto. Particularidade do Slack: erro de API vem como
 * HTTP 200 com {@code {"ok":false,"error":"..."}} — viramos {@code ProviderError} com o campo
 * {@code error}, sem nunca logar/ecoar o token.
 */
@Component
public class SlackOAuthHttpClient implements SlackOAuthClient {

    private static final String TOKEN_URL = "https://slack.com/api/oauth.v2.access";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient http;
    private final ObjectMapper mapper;
    private final String clientId;
    private final String clientSecret;

    public SlackOAuthHttpClient(
            ObjectMapper mapper,
            @Value("${nora.integrations.slack.client-id:}") String clientId,
            @Value("${nora.integrations.slack.client-secret:}") String clientSecret) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public TokenResponse exchangeCode(String code, String redirectUri) {
        try {
            String body =
                    http.post()
                            .uri(TOKEN_URL)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(
                                    BodyInserters.fromFormData("client_id", clientId)
                                            .with("client_secret", clientSecret)
                                            .with("code", code)
                                            .with("redirect_uri", redirectUri))
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            if (!json.path("ok").asBoolean(false)) {
                throw new IntegrationException.ProviderError(
                        "slack", "oauth: " + json.path("error").asText("resposta sem ok"));
            }
            String botToken = json.path("access_token").asText(null);
            if (botToken == null) {
                throw new IntegrationException.ProviderError("slack", "resposta sem access_token");
            }
            return new TokenResponse(
                    botToken,
                    json.path("team").path("name").asText(null),
                    json.path("scope").asText(null));
        } catch (IntegrationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IntegrationException.ProviderError("slack", "oauth: " + reason(ex));
        }
    }

    /** Status HTTP puro em falha de transporte — o body do Slack pode ecoar dados sensíveis. */
    private static String reason(Exception ex) {
        if (ex instanceof WebClientResponseException http) {
            return String.valueOf(http.getStatusCode().value());
        }
        return ex.getMessage();
    }
}
