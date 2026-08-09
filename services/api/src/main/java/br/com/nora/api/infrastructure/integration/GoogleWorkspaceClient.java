package br.com.nora.api.infrastructure.integration;

import br.com.nora.api.application.integration.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Calls to the Google Workspace APIs used by the Flows actions: Gmail (send e-mail as the connected
 * user) and Calendar (create an event in the primary calendar). No SDK — minimal, visible payloads.
 * A failure becomes {@code ProviderError} with the status (the engine records it in the execution
 * log).
 */
@Component
public class GoogleWorkspaceClient {

    private static final String GMAIL_SEND_URL =
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    private static final String CALENDAR_EVENTS_URL =
            "https://www.googleapis.com/calendar/v3/calendars/primary/events";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    // RFC3339 requires seconds; OffsetDateTime.toString() omits them when zero
    // (e.g. "2026-06-13T10:00-03:00") and Calendar responds 400.
    private static final DateTimeFormatter RFC3339 =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    private final WebClient http;
    private final ObjectMapper mapper;

    public GoogleWorkspaceClient(ObjectMapper mapper) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
    }

    /** Sends e-mail through the connected account. Returns the message id in Gmail. */
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

    /** Creates an event in the primary calendar. Returns the event link (htmlLink). */
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
                        Map.of("dateTime", rfc3339(start)),
                        "end",
                        Map.of("dateTime", rfc3339(end)));
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
     * Minimal RFC 2822 MIME with UTF-8 HTML. Subject in RFC 2047 (B-encoding) for accents. "From:
     * me" — Gmail replaces it with the authenticated account.
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

    static String rfc3339(OffsetDateTime value) {
        return RFC3339.format(value);
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
        String reason;
        if (ex instanceof WebClientResponseException http) {
            String detail = http.getResponseBodyAsString();
            reason =
                    http.getStatusCode().value()
                            + (detail.isBlank()
                                    ? ""
                                    : " — " + detail.substring(0, Math.min(detail.length(), 300)));
        } else {
            reason = ex.getMessage();
        }
        return new IntegrationException.ProviderError("google", api + ": " + reason);
    }
}
