package br.com.nora.api.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Calls to Todoist's REST API v2 used by the Flows {@code todoist_create_task} action. No SDK —
 * minimal, visible payload. A failure becomes a {@code ProviderError} with status + body excerpt
 * (≤300 chars), the pattern of the other wave 1 clients.
 */
@Component
public class TodoistClient {

    private static final String TASKS_URL = "https://api.todoist.com/rest/v2/tasks";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient http;
    private final ObjectMapper mapper;

    public TodoistClient(ObjectMapper mapper) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
    }

    /** Creates a task in the connected user's Inbox. Returns the URL of the created task. */
    public String createTask(String accessToken, String content, String description) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        if (description != null && !description.isBlank()) {
            payload.put("description", description);
        }
        try {
            String response =
                    http.post()
                            .uri(TASKS_URL)
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(payload)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(response == null ? "{}" : response);
            return json.path("url").asText("(tarefa criada)");
        } catch (Exception ex) {
            throw ProviderErrors.of("todoist", "tasks", ex);
        }
    }
}
