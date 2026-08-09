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
 * Calls to Microsoft Graph used by the Flows actions: Outlook ({@code POST /me/sendMail}, HTML
 * body) and Calendar ({@code POST /me/events}). No SDK — minimal, visible payloads. A failure
 * becomes a {@code ProviderError} with status + body excerpt, the {@link GoogleWorkspaceClient}
 * pattern.
 */
@Component
public class MicrosoftGraphClient {

    private static final String GRAPH_BASE = "https://graph.microsoft.com/v1.0";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    // Graph uses dateTimeTimeZone: local dateTime with NO offset + separate timeZone (IANA ok).
    // Same trap as Google Calendar (GoogleWorkspaceClient.RFC3339): seconds are MANDATORY —
    // OffsetDateTime.toString() omits them when zeroed and Graph answers 400.
    private static final DateTimeFormatter GRAPH_LOCAL =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final WebClient http;
    private final ObjectMapper mapper;

    public MicrosoftGraphClient(ObjectMapper mapper) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
    }

    /** Sends e-mail through the connected Microsoft account (202 with no body when accepted). */
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
     * Creates an event in the connected account's primary calendar. Returns the event link
     * (webLink).
     *
     * @param timeZone IANA (e.g. {@code America/Sao_Paulo}) — Graph interprets the local dateTimes
     *     in this time zone
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

    /** Local date-time WITH seconds (the offset is left out — Graph uses the timeZone field). */
    public static String graphLocal(OffsetDateTime value) {
        return GRAPH_LOCAL.format(value);
    }
}
