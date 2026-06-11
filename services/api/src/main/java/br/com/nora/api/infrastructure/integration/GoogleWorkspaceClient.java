package br.com.nora.api.infrastructure.integration;

import br.com.nora.api.application.integration.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Chamadas às APIs Google Workspace usadas pelas ações do Flows: Gmail (enviar e-mail como o
 * usuário conectado) e Calendar (criar evento no calendário primário). Sem SDK — payloads mínimos e
 * visíveis. Falha vira {@code ProviderError} com o status (o engine registra no log da execução).
 */
@Component
public class GoogleWorkspaceClient {

    private static final String GMAIL_SEND_URL =
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    private static final String CALENDAR_EVENTS_URL =
            "https://www.googleapis.com/calendar/v3/calendars/primary/events";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient http;
    private final ObjectMapper mapper;

    public GoogleWorkspaceClient(ObjectMapper mapper) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
    }

    /** Envia e-mail pela conta conectada. Retorna o id da mensagem no Gmail. */
    public String sendGmail(String accessToken, String to, String subject, String htmlBody) {
        String raw = base64Url(buildMime(to, subject, htmlBody));
        try {
            String body =
                    http.post()
                            .uri(GMAIL_SEND_URL)
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("raw", raw))
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            return json.path("id").asText("?");
        } catch (Exception ex) {
            throw providerError("gmail", ex);
        }
    }

    /** Cria evento no calendário primário. Retorna o link do evento (htmlLink). */
    public String createCalendarEvent(
            String accessToken,
            String title,
            String description,
            OffsetDateTime start,
            OffsetDateTime end) {
        Map<String, Object> event =
                Map.of(
                        "summary",
                        title,
                        "description",
                        description == null ? "" : description,
                        "start",
                        Map.of("dateTime", start.toString()),
                        "end",
                        Map.of("dateTime", end.toString()));
        try {
            String body =
                    http.post()
                            .uri(CALENDAR_EVENTS_URL)
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(event)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            return json.path("htmlLink").asText("(evento criado)");
        } catch (Exception ex) {
            throw providerError("calendar", ex);
        }
    }

    /**
     * MIME RFC 2822 mínimo com HTML UTF-8. Subject em RFC 2047 (B-encoding) para acentos. "From:
     * me" — o Gmail substitui pela conta autenticada.
     */
    static String buildMime(String to, String subject, String htmlBody) {
        String encodedSubject =
                "=?UTF-8?B?"
                        + Base64.getEncoder()
                                .encodeToString(subject.getBytes(StandardCharsets.UTF_8))
                        + "?=";
        return "To: "
                + to
                + "\r\n"
                + "Subject: "
                + encodedSubject
                + "\r\n"
                + "MIME-Version: 1.0\r\n"
                + "Content-Type: text/html; charset=UTF-8\r\n"
                + "Content-Transfer-Encoding: base64\r\n"
                + "\r\n"
                + Base64.getMimeEncoder().encodeToString(htmlBody.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private IntegrationException providerError(String api, Exception ex) {
        if (ex instanceof IntegrationException ie) {
            return ie;
        }
        String reason =
                ex instanceof WebClientResponseException http
                        ? String.valueOf(http.getStatusCode().value())
                        : ex.getMessage();
        return new IntegrationException.ProviderError("google", api + ": " + reason);
    }
}
