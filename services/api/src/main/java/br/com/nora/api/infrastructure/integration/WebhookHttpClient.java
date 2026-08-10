package br.com.nora.api.infrastructure.integration;

import br.com.nora.api.application.integration.IntegrationException;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Generic JSON POST used by the Flows webhook actions ({@code call_webhook} and {@code
 * discord_post_message}). No SDK — follows the {@link SlackClient}/{@link GoogleWorkspaceClient}
 * pattern: WebClient directly, visible payload, short timeout.
 *
 * <p>Contract: 2xx returns the HTTP status; any other status or a transport failure becomes a
 * {@code ProviderError} (the engine records it in the run log and marks FAILED). The response body
 * is never echoed in the error — third-party endpoints may return sensitive data.
 */
@Component
public class WebhookHttpClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient http;

    public WebhookHttpClient() {
        this.http = WebClient.builder().build();
    }

    /**
     * JSON POST to the URL. {@code provider} identifies the source in the error (e.g. "webhook",
     * "discord").
     */
    public int postJson(String provider, String url, Map<String, String> headers, Object body) {
        try {
            ResponseEntity<Void> response =
                    http.post()
                            .uri(url)
                            .headers(h -> headers.forEach(h::set))
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .toBodilessEntity()
                            .block(TIMEOUT);
            if (response == null) {
                throw new IntegrationException.ProviderError(provider, "no response from endpoint");
            }
            return response.getStatusCode().value();
        } catch (IntegrationException ex) {
            throw ex;
        } catch (Exception ex) {
            // HTTP status only in the error — never the body (may echo sensitive endpoint data).
            String reason =
                    ex instanceof WebClientResponseException httpEx
                            ? "responded with HTTP " + httpEx.getStatusCode().value()
                            : ex.getMessage();
            throw new IntegrationException.ProviderError(provider, reason);
        }
    }
}
