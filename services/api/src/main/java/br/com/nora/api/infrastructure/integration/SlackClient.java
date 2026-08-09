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
 * Calls to Slack's Web API used by the Flows actions: {@code chat.postMessage} with the connected
 * workspace's bot token. No SDK — minimal, visible payload. Slack answers HTTP 200 even on an error
 * ({@code ok:false}); here that becomes a {@code ProviderError} with an actionable message (e.g.
 * bot outside the channel → points at /invite).
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

    /** Posts a message to the channel ({@code #vendas} name or id). Returns the message ts. */
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
            // Transport failure: HTTP status only (the body may echo sensitive data).
            String reason =
                    ex instanceof WebClientResponseException http
                            ? String.valueOf(http.getStatusCode().value())
                            : ex.getMessage();
            throw new IntegrationException.ProviderError("slack", "chat.postMessage: " + reason);
        }
    }

    /** Common errors get a fix hint — it is what the run log shows the user. */
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
