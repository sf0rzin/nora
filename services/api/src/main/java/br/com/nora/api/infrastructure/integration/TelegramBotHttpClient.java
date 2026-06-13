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
 * Adapter HTTP da Bot API do Telegram ({@code NORA_TELEGRAM_BOT_TOKEN}, bot ÚNICO do app). Sem SDK
 * — getMe (username cacheado), getUpdates one-shot (offset em memória; cada update chega uma vez) e
 * sendMessage em HTML.
 *
 * <p>ATENÇÃO de segurança: o token do bot vai na URL ({@code /bot<token>/método}) — toda falha
 * passa por {@link #reason}, que NUNCA ecoa a URL (só status + description do envelope do
 * Telegram), diferente do padrão {@code ProviderErrors} dos demais clients.
 */
@Component
public class TelegramBotHttpClient implements TelegramBotApi {

    private static final String API_BASE = "https://api.telegram.org/bot";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final String PROVIDER = "telegram";

    private final WebClient http;
    private final ObjectMapper mapper;
    private final String botToken;

    /** Username do bot (getMe) — não muda durante a vida do processo. */
    private volatile String cachedUsername;

    /** Próximo update_id a pedir no getUpdates (one-shot; perder em restart é aceitável). */
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
            throw new IntegrationException.ProviderError(PROVIDER, "getMe sem username do bot");
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

    /** Envia mensagem HTML pro chat pareado. Retorna o message_id. */
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

    /** Nome de exibição de quem mandou o /start (first_name [last_name] ou @username). */
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

    /** Chama um método da Bot API e devolve o {@code result} do envelope {@code ok/result}. */
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
                        PROVIDER, json.path("description").asText("resposta ok=false"));
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
     * Falha SEM ecoar a URL (carrega o token do bot): status + description do corpo quando o
     * Telegram a mandou.
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
                // corpo não-JSON — fica só o status
            }
            return httpEx.getStatusCode().value()
                    + (description.isBlank() ? "" : " — " + description);
        }
        // Mensagens de exceções de transporte não carregam a URL completa por padrão, mas por
        // segurança nunca repassamos nada além da classe quando o token aparecer.
        String message = ex.getMessage();
        return message != null && message.contains(botToken)
                ? ex.getClass().getSimpleName()
                : message;
    }
}
