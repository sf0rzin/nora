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
 * Chamadas à GraphQL API do Linear usadas pela ação {@code linear_create_issue} do Flows. Sem SDK —
 * queries mínimas e visíveis. O Linear responde HTTP 200 mesmo com erro GraphQL ({@code errors[]})
 * — aqui isso vira {@code ProviderError} com a primeira mensagem (≤300 chars); falha de transporte
 * segue o padrão status + corpo dos demais clients da onda 1.
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

    /** Id do primeiro team do workspace (default quando a ação não recebe {@code teamKey}). */
    public String firstTeamId(String accessToken) {
        JsonNode data =
                graphql(accessToken, "query { teams(first: 1) { nodes { id } } }", Map.of());
        JsonNode node = data.path("teams").path("nodes").path(0).path("id");
        if (!node.isTextual()) {
            throw new IntegrationException.ProviderError(
                    "linear", "teams: workspace sem nenhum team acessível pelo token");
        }
        return node.asText();
    }

    /** Resolve o id do team pela key (ex.: {@code ENG}). */
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
                    "linear", "teams: nenhum team com a key '" + teamKey + "'");
        }
        return node.asText();
    }

    /** Cria uma issue no team. Retorna a URL da issue criada. */
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
                    "linear", "issueCreate: o provedor não confirmou a criação da issue");
        }
        return issueCreate.path("issue").path("url").asText("(issue criada)");
    }

    /** POST GraphQL com Bearer token; devolve {@code data} ou lança {@code ProviderError}. */
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
                String message = errors.path(0).path("message").asText("erro GraphQL");
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
