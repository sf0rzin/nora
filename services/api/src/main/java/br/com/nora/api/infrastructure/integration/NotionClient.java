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
 * Chamadas à API do Notion usadas pela ação {@code notion_create_page} do Flows. Sem SDK — payload
 * mínimo e visível, versão pinada via {@code Notion-Version}. Falha vira {@code ProviderError} com
 * status + trecho do corpo (≤300 chars) — o corpo do Notion é acionável (ex.: página pai não
 * compartilhada com a integração).
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
     * Cria uma página filha de {@code parentPageId} com o título dado e os blocos de conteúdo
     * (heading, parágrafo, bulleted list — montados pela ação). Retorna a URL da página criada.
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
