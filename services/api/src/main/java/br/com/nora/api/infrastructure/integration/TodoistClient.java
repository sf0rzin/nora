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
 * Chamadas à REST API v2 do Todoist usadas pela ação {@code todoist_create_task} do Flows. Sem SDK
 * — payload mínimo e visível. Falha vira {@code ProviderError} com status + trecho do corpo (≤300
 * chars), padrão dos demais clients da onda 1.
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

    /** Cria uma tarefa no Inbox do usuário conectado. Retorna a URL da tarefa criada. */
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
