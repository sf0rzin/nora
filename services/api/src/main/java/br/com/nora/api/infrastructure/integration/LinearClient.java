package br.com.nora.api.infrastructure.integration;

import br.com.nora.api.application.integration.IntegrationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Calls to Linear's GraphQL API used by the Flows {@code linear_create_issue} action. No SDK —
 * minimal, visible queries. Linear answers HTTP 200 even on a GraphQL error ({@code errors[]}) —
 * here that becomes a {@code ProviderError} with the first message (≤300 chars); a transport
 * failure follows the status + body pattern of the other wave 1 clients.
 */
@Component
public class LinearClient {

    private static final String GRAPHQL_URL = "https://api.linear.app/graphql";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient http;
    private final ObjectMapper mapper;

    public LinearClient(ObjectMapper mapper) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
    }

    /** Id of the workspace's first team (default when the action gets no {@code teamKey}). */
    public String firstTeamId(String accessToken) {
        JsonNode data =
                graphql(accessToken, "query { teams(first: 1) { nodes { id } } }", Map.of());
        JsonNode node = data.path("teams").path("nodes").path(0).path("id");
        if (!node.isTextual()) {
            throw new IntegrationException.ProviderError(
                    "linear", "teams: workspace has no team accessible with this token");
        }
        return node.asText();
    }

    /** Resolves the team id by key (e.g. {@code ENG}). */
    public String teamIdByKey(String accessToken, String teamKey) {
        JsonNode data =
                graphql(
                        accessToken,
                        "query($key: String!) { teams(filter: { key: { eq: $key } })"
                                + " { nodes { id } } }",
                        Map.of("key", teamKey));
        JsonNode node = data.path("teams").path("nodes").path(0).path("id");
        if (!node.isTextual()) {
            throw new IntegrationException.ProviderError(
                    "linear", "teams: no team with key '" + teamKey + "'");
        }
        return node.asText();
    }

    /** Creates an issue in the team. Returns the URL of the created issue. */
    public String createIssue(String accessToken, String teamId, String title, String description) {
        JsonNode data =
                graphql(
                        accessToken,
                        "mutation($input: IssueCreateInput!) { issueCreate(input: $input)"
                                + " { success issue { url } } }",
                        Map.of(
                                "input",
                                Map.of(
                                        "teamId", teamId,
                                        "title", title,
                                        "description", description == null ? "" : description)));
        JsonNode issueCreate = data.path("issueCreate");
        if (!issueCreate.path("success").asBoolean(false)) {
            throw new IntegrationException.ProviderError(
                    "linear", "issueCreate: the provider did not confirm the issue creation");
        }
        return issueCreate.path("issue").path("url").asText("(issue created)");
    }

    /** GraphQL POST with Bearer token; returns {@code data} or throws {@code ProviderError}. */
    private JsonNode graphql(String accessToken, String query, Map<String, Object> variables) {
        try {
            String response =
                    http.post()
                            .uri(GRAPHQL_URL)
                            .header("Authorization", "Bearer " + accessToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(Map.of("query", query, "variables", variables))
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(response == null ? "{}" : response);
            JsonNode errors = json.path("errors");
            if (errors.isArray() && !errors.isEmpty()) {
                String message = errors.path(0).path("message").asText("GraphQL error");
                throw new IntegrationException.ProviderError(
                        "linear",
                        "graphql: " + message.substring(0, Math.min(message.length(), 300)));
            }
            return json.path("data");
        } catch (Exception ex) {
            throw ProviderErrors.of("linear", "graphql", ex);
        }
    }
}
