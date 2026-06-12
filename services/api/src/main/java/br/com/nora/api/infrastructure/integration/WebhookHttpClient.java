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
 * POST JSON genérico usado pelas ações de webhook do Flows ({@code call_webhook} e {@code
 * discord_post_message}). Sem SDK — segue o padrão do {@link SlackClient}/{@link
 * GoogleWorkspaceClient}: WebClient direto, payload visível, timeout curto.
 *
 * <p>Contrato: 2xx retorna o status HTTP; qualquer outro status ou falha de transporte vira {@code
 * ProviderError} (o engine registra no log da execução e marca FAILED). O body da resposta nunca é
 * ecoado no erro — endpoints de terceiros podem devolver dados sensíveis.
 */
@Component
public class WebhookHttpClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final WebClient http;

    public WebhookHttpClient() {
        this.http = WebClient.builder().build();
    }

    /**
     * POST JSON na URL. {@code provider} identifica a origem no erro (ex.: "webhook", "discord").
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
                throw new IntegrationException.ProviderError(provider, "sem resposta do endpoint");
            }
            return response.getStatusCode().value();
        } catch (IntegrationException ex) {
            throw ex;
        } catch (Exception ex) {
            // Só o status HTTP no erro — nunca o body (pode ecoar dados sensíveis do endpoint).
            String reason =
                    ex instanceof WebClientResponseException httpEx
                            ? "respondeu HTTP " + httpEx.getStatusCode().value()
                            : ex.getMessage();
            throw new IntegrationException.ProviderError(provider, reason);
        }
    }
}
