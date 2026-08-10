package br.com.nora.api.infrastructure.integration;

import br.com.nora.api.application.integration.IntegrationException;
import br.com.nora.api.application.ports.TrelloApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * HTTP adapter for Trello's REST API (app key via {@code TRELLO_API_KEY} + token pasted by the
 * user): validates the token on connect ({@code GET /1/members/me}) and creates cards from the
 * Flows action.
 *
 * <p>Security WARNING: key and token go in the query string — every failure goes through {@link
 * #reason}, which NEVER echoes the URL (only status + Trello's short body, which does not return
 * credentials), unlike the {@code ProviderErrors} pattern of the other clients.
 */
@Component
public class TrelloHttpClient implements TrelloApi {

    private static final String API_BASE = "https://api.trello.com/1";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String PROVIDER = "trello";

    private final WebClient http;
    private final ObjectMapper mapper;
    private final String apiKey;

    public TrelloHttpClient(
            ObjectMapper mapper, @Value("${nora.integrations.trello.api-key:}") String apiKey) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
        this.apiKey = apiKey;
    }

    @Override
    public String validateToken(String token) {
        try {
            String body =
                    http.get()
                            .uri(API_BASE + "/members/me" + credentials(token))
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            String fullName = json.path("fullName").asText(null);
            String username = json.path("username").asText(null);
            return fullName != null && !fullName.isBlank() ? fullName : username;
        } catch (WebClientResponseException ex) {
            if (ex.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new IntegrationException.ProviderError(
                        PROVIDER,
                        "Trello refused that token — generate a new one on the authorization tab"
                                + " and paste it again");
            }
            throw new IntegrationException.ProviderError(PROVIDER, "members/me: " + reason(ex));
        } catch (Exception ex) {
            throw new IntegrationException.ProviderError(PROVIDER, "members/me: " + reason(ex));
        }
    }

    /**
     * Creates a card in the list. Returns the card's short URL.
     *
     * @param due optional due date (goes as midnight UTC of that day); null = card with no due date
     */
    public String createCard(String token, String listId, String name, String desc, LocalDate due) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("idList", listId);
        payload.put("name", name);
        payload.put("desc", desc == null ? "" : desc);
        if (due != null) {
            payload.put("due", due.toString());
        }
        try {
            String body =
                    http.post()
                            .uri(API_BASE + "/cards" + credentials(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(payload)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            return json.path("shortUrl").asText(json.path("url").asText("(card created)"));
        } catch (IntegrationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IntegrationException.ProviderError(PROVIDER, "cards: " + reason(ex));
        }
    }

    private String credentials(String token) {
        return "?key=" + url(apiKey) + "&token=" + url(token);
    }

    /**
     * Failure WITHOUT echoing the URL (it carries key+token): status + Trello's short body
     * (messages like {@code invalid token} / {@code invalid value for idList} are actionable and do
     * not return credentials).
     */
    private static String reason(Exception ex) {
        if (ex instanceof WebClientResponseException httpEx) {
            String detail = httpEx.getResponseBodyAsString();
            return httpEx.getStatusCode().value()
                    + (detail.isBlank()
                            ? ""
                            : " — " + detail.substring(0, Math.min(detail.length(), 200)));
        }
        return ex.getMessage();
    }

    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
