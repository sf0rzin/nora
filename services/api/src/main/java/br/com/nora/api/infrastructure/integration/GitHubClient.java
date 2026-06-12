package br.com.nora.api.infrastructure.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Chamadas à REST API do GitHub usadas pela ação {@code github_create_issue} do Flows. Sem SDK —
 * payload mínimo e visível. Falha vira {@code ProviderError} com status + trecho do corpo (≤300
 * chars), padrão do {@link GoogleWorkspaceClient}.
 */
@Component
public class GitHubClient {

    private static final String API_BASE = "https://api.github.com";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient http;
    private final ObjectMapper mapper;

    public GitHubClient(ObjectMapper mapper) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
    }

    /**
     * Cria uma issue em {@code owner/nome}. Retorna a URL da issue criada.
     *
     * @param repo no formato {@code owner/nome} (validado na ação/no save)
     */
    public String createIssue(
            String accessToken, String repo, String title, String body, List<String> labels) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", title);
        payload.put("body", body == null ? "" : body);
        payload.put("labels", labels);
        try {
            String response =
                    http.post()
                            .uri(API_BASE + "/repos/" + repo + "/issues")
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Accept", "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2022-11-28")
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(payload)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(response == null ? "{}" : response);
            return json.path("html_url").asText("(issue criada)");
        } catch (Exception ex) {
            // Corpo do GitHub é acionável (ex.: 404 = repo inexistente ou sem acesso do token).
            throw ProviderErrors.of("github", "issues", ex);
        }
    }
}
