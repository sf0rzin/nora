package br.com.nora.api.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Chamadas ao Microsoft Graph usadas pelas ações do Flows: Outlook ({@code POST /me/sendMail},
 * corpo HTML) e Calendar ({@code POST /me/events}). Sem SDK — payloads mínimos e visíveis. Falha
 * vira {@code ProviderError} com status + trecho do corpo, padrão do {@link GoogleWorkspaceClient}.
 */
@Component
public class MicrosoftGraphClient {

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    // O Graph usa dateTimeTimeZone: dateTime local SEM offset + timeZone separado (IANA aceito).
    // Mesma armadilha do Google Calendar (GoogleWorkspaceClient.RFC3339): segundos são
    // OBRIGATÓRIOS — OffsetDateTime.toString() os omite quando zerados e o Graph responde 400.
    private static final DateTimeFormatter GRAPH_LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final WebClient http;
    private final ObjectMapper mapper;

    public MicrosoftGraphClient(ObjectMapper mapper) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
    }

    /** Envia e-mail pela conta Microsoft conectada (202 sem corpo quando aceito). */
    public void sendMail(String accessToken, String to, String subject, String htmlBody) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("subject", subject);
        message.put("body", Map.of("contentType", "HTML", "content", htmlBody));
        message.put("toRecipients", List.of(Map.of("emailAddress", Map.of("address", to))));
        try {
            http.post()
                    .uri(GRAPH_BASE + "/me/sendMail")
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("message", message, "saveToSentItems", true))
                    .retrieve()
                    .toBodilessEntity()
                    .block(TIMEOUT);
        } catch (Exception ex) {
            throw ProviderErrors.of("microsoft", "sendMail", ex);
        }
    }

    /**
     * Cria evento no calendário primário da conta conectada. Retorna o link do evento (webLink).
     *
     * @param timeZone IANA (ex.: {@code America/Sao_Paulo}) — o Graph interpreta os dateTimes
     *     locais neste fuso
     */
    public String createEvent(
            String accessToken,
            String title,
            String description,
            OffsetDateTime start,
            OffsetDateTime end,
            String timeZone) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("subject", title);
        event.put(
                "body",
                Map.of("contentType", "Text", "content", description == null ? "" : description));
        event.put("start", Map.of("dateTime", graphLocal(start), "timeZone", timeZone));
        event.put("end", Map.of("dateTime", graphLocal(end), "timeZone", timeZone));
        try {
            String body =
                    http.post()
                            .uri(GRAPH_BASE + "/me/events")
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(event)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            return json.path("webLink").asText("(evento criado)");
        } catch (Exception ex) {
            throw ProviderErrors.of("microsoft", "events", ex);
        }
    }

    /** Data-hora local COM segundos (o offset fica de fora — o Graph usa o campo timeZone). */
    public static String graphLocal(OffsetDateTime value) {
        return GRAPH_LOCAL.format(value);
    }
}
