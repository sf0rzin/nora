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
 * Calls to the GitHub REST API used by the Flows {@code github_create_issue} action. No SDK —
 * minimal, visible payload. A failure becomes {@code ProviderError} with status + body excerpt
 * (≤300 chars), the {@link GoogleWorkspaceClient} pattern.
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
     * Creates an issue in {@code owner/nome}. Returns the URL of the created issue.
     *
     * @param repo in the {@code owner/nome} format (validated in the action/on save)
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
            // The GitHub body is actionable (e.g. 404 = nonexistent repo or token without access).
            throw ProviderErrors.of("github", "issues", ex);
        }
    }
}
