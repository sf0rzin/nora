package br.com.nora.api.infrastructure.embedding;

import br.com.nora.api.application.ports.EmbeddingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Provider-agnostic HTTP {@link EmbeddingClient} (ADR 0004). Default Gemini; OpenAI supported by
 * the same code (only the endpoint/shape and the credential change). Both emit the same configured
 * dimension ({@code nora.embedding.dimension}). No credential for the active provider → {@link
 * #isEnabled()} false and the pipeline treats it as a no-op (embeddings are best-effort, they never
 * bring down the analysis).
 */
@Component
public class HttpEmbeddingClient implements EmbeddingClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final String provider;
    private final String model;
    private final int dimension;
    private final String geminiBaseUrl;
    private final String openAiBaseUrl;
    private final String apiKey;

    public HttpEmbeddingClient(
            ObjectMapper mapper,
            @Value("${nora.embedding.provider:gemini}") String provider,
            @Value("${nora.embedding.model:text-embedding-004}") String model,
            @Value("${nora.embedding.dimension:768}") int dimension,
            @Value(
                            "${nora.embedding.gemini-base-url:https://generativelanguage.googleapis.com/v1beta}")
                    String geminiBaseUrl,
            @Value("${nora.embedding.openai-base-url:https://api.openai.com/v1}")
                    String openAiBaseUrl,
            @Value("${nora.embedding.gemini-api-key:}") String geminiKey,
            @Value("${nora.embedding.openai-api-key:}") String openAiKey) {
        this.mapper = mapper;
        this.provider = provider.toLowerCase();
        this.model = model;
        this.dimension = dimension;
        this.geminiBaseUrl = stripSlash(geminiBaseUrl);
        this.openAiBaseUrl = stripSlash(openAiBaseUrl);
        String key = "openai".equals(this.provider) ? openAiKey : geminiKey;
        // 'unset' is the Bicep sentinel (KV does not accept an empty secret) → treat as disabled.
        this.apiKey = (key == null || key.isBlank() || "unset".equals(key)) ? "" : key;
    }

    @Override
    public String modelId() {
        return provider + ":" + model;
    }

    @Override
    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public float[] embed(String text) {
        if (!isEnabled()) {
            throw new EmbeddingException(
                    "embedding disabled (no credential for provider " + provider + ")");
        }
        try {
            return "openai".equals(provider) ? embedOpenAi(text) : embedGemini(text);
        } catch (EmbeddingException e) {
            throw e;
        } catch (Exception e) {
            throw new EmbeddingException("failed to generate embedding (" + provider + ")", e);
        }
    }

    private float[] embedGemini(String text) throws Exception {
        String url = geminiBaseUrl + "/models/" + model + ":embedContent?key=" + apiKey;
        var payload = mapper.createObjectNode();
        payload.put("model", "models/" + model);
        payload.putObject("content").putArray("parts").addObject().put("text", text);
        payload.put("outputDimensionality", dimension);
        JsonNode resp = post(url, payload.toString(), null);
        return toArray(resp.path("embedding").path("values"));
    }

    private float[] embedOpenAi(String text) throws Exception {
        String url = openAiBaseUrl + "/embeddings";
        var payload = mapper.createObjectNode();
        payload.put("input", text);
        payload.put("model", model);
        payload.put("dimensions", dimension);
        JsonNode resp = post(url, payload.toString(), "Bearer " + apiKey);
        return toArray(resp.path("data").path(0).path("embedding"));
    }

    private JsonNode post(String url, String body, String auth) throws Exception {
        HttpRequest.Builder b =
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        if (auth != null) {
            b.header("Authorization", auth);
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            throw new EmbeddingException("provider " + provider + " responded " + res.statusCode());
        }
        return mapper.readTree(res.body());
    }

    private float[] toArray(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) {
            throw new EmbeddingException("response without embedding vector");
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = (float) values.get(i).asDouble();
        }
        return out;
    }

    private static String stripSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
