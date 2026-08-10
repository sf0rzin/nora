package br.com.nora.api.infrastructure.integration;

import br.com.nora.api.application.integration.IntegrationException;
import br.com.nora.api.application.ports.TelegramBotApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * HTTP adapter for Telegram's Bot API ({@code NORA_TELEGRAM_BOT_TOKEN}, the app's SINGLE bot). No
 * SDK — getMe (cached username), one-shot getUpdates (in-memory offset; each update arrives once)
 * and sendMessage in HTML.
 *
 * <p>Security WARNING: the bot token goes in the URL ({@code /bot<token>/method}) — every failure
 * goes through {@link #reason}, which NEVER echoes the URL (only status + description from
 * Telegram's envelope), unlike the {@code ProviderErrors} pattern of the other clients.
 */
@Component
public class TelegramBotHttpClient implements TelegramBotApi {

    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String PROVIDER = "telegram";

    private final WebClient http;
    private final ObjectMapper mapper;
    private final String botToken;

    /** Bot username (getMe) — does not change during the process lifetime. */
    private volatile String cachedUsername;

    /** Next update_id to ask for in getUpdates (one-shot; losing it on restart is acceptable). */
    private final AtomicLong nextOffset = new AtomicLong(0);

    public TelegramBotHttpClient(
            ObjectMapper mapper,
            @Value("${nora.integrations.telegram.bot-token:}") String botToken) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
        this.botToken = botToken;
    }

    @Override
    public String botUsername() {
        String cached = cachedUsername;
        if (cached != null) {
            return cached;
        }
        JsonNode result = call("getMe", null);
        String username = result.path("username").asText(null);
        if (username == null || username.isBlank()) {
            throw new IntegrationException.ProviderError(PROVIDER, "getMe with no bot username");
        }
        cachedUsername = username;
        return username;
    }

    @Override
    public List<StartCommand> pollStartCommands() {
        long offset = nextOffset.get();
        String query = "?timeout=0&allowed_updates=%5B%22message%22%5D";
        if (offset > 0) {
            query += "&offset=" + offset;
        }
        JsonNode result = call("getUpdates" + query, null);
        List<StartCommand> commands = new ArrayList<>();
        long maxUpdateId = offset - 1;
        for (JsonNode update : result) {
            maxUpdateId = Math.max(maxUpdateId, update.path("update_id").asLong());
            JsonNode message = update.path("message");
            String text = message.path("text").asText("");
            if (!text.startsWith("/start ")) {
                continue;
            }
            String chatId = message.path("chat").path("id").asText(null);
            if (chatId == null) {
                continue;
            }
            commands.add(
                    new StartCommand(
                            chatId, text.substring("/start ".length()).trim(), from(message)));
        }
        if (maxUpdateId >= offset) {
            nextOffset.set(maxUpdateId + 1);
        }
        return commands;
    }

    /** Sends an HTML message to the paired chat. Returns the message_id. */
    public String sendMessageHtml(String chatId, String html) {
        JsonNode result =
                call(
                        "sendMessage",
                        Map.of(
                                "chat_id",
                                chatId,
                                "text",
                                html,
                                "parse_mode",
                                "HTML",
                                "disable_web_page_preview",
                                true));
        return result.path("message_id").asText("?");
    }

    /** Display name of whoever sent /start (first_name [last_name] or @username). */
    private static String from(JsonNode message) {
        JsonNode from = message.path("from");
        String first = from.path("first_name").asText("");
        String last = from.path("last_name").asText("");
        String name = (first + " " + last).strip();
        if (!name.isBlank()) {
            return name;
        }
        String username = from.path("username").asText("");
        return username.isBlank() ? null : "@" + username;
    }

    /** Calls a Bot API method and returns the {@code result} of the {@code ok/result} envelope. */
    private JsonNode call(String methodAndQuery, Map<String, Object> jsonBody) {
        requireConfigured();
        try {
            WebClient.RequestHeadersSpec<?> request;
            if (jsonBody == null) {
                request = http.get().uri(API_BASE + botToken + "/" + methodAndQuery);
            } else {
                request =
                        http.post()
                                .uri(API_BASE + botToken + "/" + methodAndQuery)
                                .contentType(MediaType.APPLICATION_JSON)
                                .bodyValue(jsonBody);
            }
            String body = request.retrieve().bodyToMono(String.class).block(TIMEOUT);
            JsonNode json = mapper.readTree(body == null ? "{}" : body);
            if (!json.path("ok").asBoolean(false)) {
                throw new IntegrationException.ProviderError(
                        PROVIDER, json.path("description").asText("response with ok=false"));
            }
            return json.path("result");
        } catch (IntegrationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IntegrationException.ProviderError(PROVIDER, reason(ex));
        }
    }

    private void requireConfigured() {
        if (botToken == null || botToken.isBlank()) {
            throw new IntegrationException.NotConfigured(PROVIDER);
        }
    }

    /**
     * Failure WITHOUT echoing the URL (it carries the bot token): status + body description when
     * Telegram sent one.
     */
    private String reason(Exception ex) {
        if (ex instanceof WebClientResponseException httpEx) {
            String description = "";
            try {
                description =
                        mapper.readTree(httpEx.getResponseBodyAsString())
                                .path("description")
                                .asText("");
            } catch (Exception ignored) {
                // non-JSON body — only the status remains
            }
            return httpEx.getStatusCode().value()
                    + (description.isBlank() ? "" : " — " + description);
        }
        // Transport exception messages do not carry the full URL by default, but to be safe we
        // never pass along anything beyond the class name when the token shows up.
        String message = ex.getMessage();
        return message != null && message.contains(botToken)
                ? ex.getClass().getSimpleName()
                : message;
    }
}
