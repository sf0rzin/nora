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
 * Calls to the Notion API used by the Flows {@code notion_create_page} action. No SDK — minimal,
 * visible payload, version pinned via {@code Notion-Version}. A failure becomes a {@code
 * ProviderError} with status + body excerpt (≤300 chars) — Notion's body is actionable (e.g. parent
 * page not shared with the integration).
 */
@Component
public class NotionClient {

    private static final String PAGES_URL = "https://api.notion.com/v1/pages";
    private static final String NOTION_VERSION = "2022-06-28";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WebClient http;
    private final ObjectMapper mapper;

    public NotionClient(ObjectMapper mapper) {
        this.http = WebClient.builder().build();
        this.mapper = mapper;
    }

    /**
     * Creates a child page of {@code parentPageId} with the given title and the content blocks
     * (heading, paragraph, bulleted list — assembled by the action). Returns the URL of the created
     * page.
     */
    public String createPage(
            String accessToken, String parentPageId, String title, List<Object> children) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("parent", Map.of("page_id", parentPageId));
        payload.put(
                "properties",
                Map.of(
                        "title",
                        Map.of("title", List.of(Map.of("text", Map.of("content", title))))));
        payload.put("children", children);
        try {
            String response =
                    http.post()
                            .uri(PAGES_URL)
                            .header("Authorization", "Bearer " + accessToken)
                            .header("Notion-Version", NOTION_VERSION)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(payload)
                            .retrieve()
                            .bodyToMono(String.class)
                            .block(TIMEOUT);
            JsonNode json = mapper.readTree(response == null ? "{}" : response);
            return json.path("url").asText("(página criada)");
        } catch (Exception ex) {
            throw ProviderErrors.of("notion", "pages", ex);
        }
    }
}
