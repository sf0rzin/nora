package br.com.nora.api.infrastructure.integration;

import br.com.nora.api.application.integration.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Chamadas à Web API do Slack usadas pelas ações do Flows: {@code chat.postMessage} com o bot token
 * da workspace conectada. Sem SDK — payload mínimo e visível. O Slack responde HTTP 200 mesmo em
 * erro ({@code ok:false}); aqui isso vira {@code ProviderError} com mensagem acionável (ex.: bot
 * fora do canal → orienta o /invite).
 */
@Component
public class SlackClient {

    private static final String POST_MESSAGE_URL = "https://slack.com/api/chat.postMessage";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient http;
    private final ObjectMapper mapper;

    public SlackClient(ObjectMapper mapper) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
    }

    /** Posta mensagem no canal (nome {@code #vendas} ou id). Retorna o ts da mensagem. */
    public String postMessage(String botToken, String channel, String text) {
        try {
            String body =
                    http.post()
                            .uri(POST_MESSAGE_URL)
                            .header("Authorization", "Bearer " + botToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("channel", channel, "text", text))
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            if (!json.path("ok").asBoolean(false)) {
                throw postError(channel, json.path("error").asText("resposta sem ok"));
            }
            return json.path("ts").asText("?");
        } catch (IntegrationException ex) {
            throw ex;
        } catch (Exception ex) {
            // Falha de transporte: só o status HTTP (o body pode ecoar dados sensíveis).
            String reason =
                    ex instanceof WebClientResponseException http
                            ? String.valueOf(http.getStatusCode().value())
                            : ex.getMessage();
            throw new IntegrationException.ProviderError("slack", "chat.postMessage: " + reason);
        }
    }

    /** Erros comuns ganham orientação de correção — é o que o log da execução mostra ao usuário. */
    private static IntegrationException postError(String channel, String error) {
        String hint =
                switch (error) {
                    case "not_in_channel", "channel_not_found" ->
                            " — o bot precisa estar no canal: convide com /invite @NORA em "
                                    + channel;
                    default -> "";
                };
        return new IntegrationException.ProviderError("slack", "chat.postMessage: " + error + hint);
    }
}
