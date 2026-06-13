package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Sentiment;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.nlp.SplitDtos;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Fluxo do split-preview (arquivo .txt com varias reunioes concatenadas): signup -> login -> POST
 * /meetings/split-preview com worker stubado. Preview NAO cria reuniao nem persiste nada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(SplitPreviewIntegrationTest.StubWorkerConfig.class)
class SplitPreviewIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("nora")
                    .withUsername("nora")
                    .withPassword("nora_dev");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void useJdkHttpClient() {
        rest.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    @Test
    void splitPreviewReturnsSegmentsAndDoesNotPersistAnything() throws Exception {
        String token = signupAndLogin("split@nora.dev", "SenhaForte123", "Owner Split");

        // Sem \n final: 4 linhas exatas (trailing newline viraria 5a linha vazia).
        String content =
                "=== Reuniao A ===\n"
                        + "Fala da primeira reuniao.\n"
                        + "=== Reuniao B ===\n"
                        + "Fala da segunda reuniao.";
        ResponseEntity<String> resp =
                multipartSplitPreview(
                        token, "reunioes.txt", content.getBytes(StandardCharsets.UTF_8));

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = mapper.readTree(resp.getBody());

        assertThat(body.get("totalLines").asInt()).isEqualTo(4);
        assertThat(body.get("segments").size()).isEqualTo(2);
        JsonNode first = body.get("segments").get(0);
        assertThat(first.get("index").asInt()).isEqualTo(1);
        assertThat(first.get("title").asText()).isEqualTo("Reuniao A");
        assertThat(first.get("startLine").asInt()).isEqualTo(1);
        assertThat(first.get("endLine").asInt()).isEqualTo(2);
        assertThat(first.get("confidence").asDouble()).isEqualTo(0.9);
        assertThat(first.get("preview").asText()).contains("[[PERSON_NAME_1]]");
        JsonNode second = body.get("segments").get(1);
        assertThat(second.get("startLine").asInt()).isEqualTo(3);
        assertThat(second.get("endLine").asInt()).isEqualTo(4);
        assertThat(body.get("metadata").get("modelVersion").asText()).isEqualTo("stub-split");
        assertThat(body.get("metadata").get("promptVersion").asText())
                .isEqualTo("meeting-split-v1");

        // Preview nao persiste: nenhuma reuniao criada para o tenant.
        JsonNode list = authGet("/meetings", token).read(HttpStatus.OK);
        assertThat(list.get("totalItems").asInt()).isZero();
    }

    @Test
    void splitPreviewRejectsNonTxtWithClearMessage() throws Exception {
        String token = signupAndLogin("splitvtt@nora.dev", "SenhaForte123", "Owner Vtt");

        ResponseEntity<String> resp =
                multipartSplitPreview(
                        token,
                        "legendas.vtt",
                        "WEBVTT\n\n00:00.000 --> 00:01.000\nola".getBytes(StandardCharsets.UTF_8));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = mapper.readTree(resp.getBody());
        assertThat(body.get("code").asText()).isEqualTo("SPLIT_UNSUPPORTED_FORMAT");
        assertThat(body.get("message").asText()).contains(".txt por enquanto");
    }

    @Test
    void splitPreviewRejectsTranscriptOverCharLimit() throws Exception {
        String token = signupAndLogin("splitbig@nora.dev", "SenhaForte123", "Owner Big");

        // 2MB de texto: passa no max-file-size (10MB) mas estoura o cap de 1M chars
        // do transcript (mesmo limite do upload normal / worker) => 413 da NOSSA validacao.
        byte[] big = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) 'a');
        ResponseEntity<String> resp = multipartSplitPreview(token, "grande.txt", big);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        JsonNode body = mapper.readTree(resp.getBody());
        assertThat(body.get("code").asText()).isEqualTo("TRANSCRIPT_TOO_LARGE");
    }

    @Test
    void splitPreviewRejectsMultipartOverSpringLimit() throws Exception {
        String token = signupAndLogin("splithuge@nora.dev", "SenhaForte123", "Owner Huge");

        // 11MB > max-file-size de 10MB: Spring/Tomcat rejeita antes do controller.
        // Dependendo do container, a rejeicao vem como 413 OU como conexao abortada
        // (Tomcat para de consumir o stream) — ambos provam que o payload nao entra.
        byte[] huge = new byte[11 * 1024 * 1024];
        java.util.Arrays.fill(huge, (byte) 'a');
        try {
            ResponseEntity<String> resp = multipartSplitPreview(token, "enorme.txt", huge);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        } catch (org.springframework.web.client.ResourceAccessException expected) {
            // conexao abortada pelo servidor durante o upload — rejeicao confirmada.
        }
    }

    @Test
    void splitPreviewRejectsEmptyFile() throws Exception {
        String token = signupAndLogin("splitempty@nora.dev", "SenhaForte123", "Owner Empty");
        ResponseEntity<String> resp = multipartSplitPreview(token, "vazio.txt", new byte[0]);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = mapper.readTree(resp.getBody());
        assertThat(body.get("code").asText()).isEqualTo("EMPTY_TRANSCRIPT");
    }

    @Test
    void splitPreviewRequiresAuthentication() {
        ResponseEntity<String> resp =
                rest.postForEntity(
                        "/meetings/split-preview",
                        new HttpEntity<>(new HttpHeaders()),
                        String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /* ---------- helpers ---------- */

    private String signupAndLogin(String email, String pwd, String name) throws Exception {
        JsonNode signup =
                postJson(
                                "/auth/signup",
                                Map.of("email", email, "password", pwd, "displayName", name))
                        .read(HttpStatus.CREATED);
        String verifyToken = signup.get("emailVerificationDevToken").asText();
        postJson("/auth/verify-email", Map.of("token", verifyToken)).read(HttpStatus.NO_CONTENT);
        JsonNode login =
                postJson("/auth/login", Map.of("email", email, "password", pwd))
                        .read(HttpStatus.OK);
        return login.get("accessToken").asText();
    }

    private ResponseEntity<String> multipartSplitPreview(
            String token, String filename, byte[] fileBytes) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        HttpHeaders fileH = new HttpHeaders();
        fileH.setContentType(MediaType.TEXT_PLAIN);
        ByteArrayResource fileResource =
                new ByteArrayResource(fileBytes) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                };
        body.add("file", new HttpEntity<>(fileResource, fileH));
        body.add("language", "pt-BR");

        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);
        return rest.postForEntity("/meetings/split-preview", entity, String.class);
    }

    private RequestExec postJson(String path, Map<String, ?> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
        return new RequestExec(rest.postForEntity(path, entity, String.class));
    }

    private RequestExec authGet(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new RequestExec(
                rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class));
    }

    private final class RequestExec {
        final ResponseEntity<String> response;

        RequestExec(ResponseEntity<String> r) {
            this.response = r;
        }

        JsonNode read(HttpStatus expected) throws Exception {
            assertThat(response.getStatusCode())
                    .as("body=%s", response.getBody())
                    .isEqualTo(expected);
            return response.getBody() == null
                    ? mapper.createObjectNode()
                    : mapper.readTree(response.getBody());
        }
    }

    /**
     * Stub determinista do worker para split: 2 segmentos fixos cobrindo 4 linhas, com preview ja
     * "redigido" (placeholder de PII) — espelha o contrato do worker /split.
     */
    @TestConfiguration
    static class StubWorkerConfig {
        @Bean
        @Primary
        NlpWorkerClient stubWorker() {
            return new NlpWorkerClient() {
                @Override
                public AnalysisResult analyze(
                        UUID meetingId,
                        UUID tenantId,
                        String language,
                        String transcript,
                        Optional<TenantContext> tenantContext,
                        Optional<MeetingGoal> goal) {
                    MeetingAnalysis analysis =
                            MeetingAnalysis.newAnalysis(
                                    meetingId,
                                    tenantId,
                                    "Resumo stub para a reuniao em teste integrado.",
                                    Sentiment.NEUTRAL,
                                    List.of("topico1"),
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    List.of(),
                                    "stub-1",
                                    "meeting-analysis-v1",
                                    100,
                                    50,
                                    10,
                                    0);
                    return AnalysisResult.of(analysis);
                }

                @Override
                public WorkerDtos.LiveAnalyzeResponse analyzeLive(
                        String transcriptChunk,
                        String language,
                        WorkerDtos.LiveHighlights previousHighlights) {
                    return new WorkerDtos.LiveAnalyzeResponse(
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            new WorkerDtos.LiveMetadata(0, 0, 0, 0, "stub-live"));
                }

                @Override
                public SplitDtos.SplitResponse split(String transcript, String language) {
                    int totalLines = transcript.split("\n", -1).length;
                    // espelha o worker: trailing newline vira linha vazia coberta pela cauda.
                    return new SplitDtos.SplitResponse(
                            List.of(
                                    new SplitDtos.SegmentDto(
                                            1,
                                            "Reuniao A",
                                            1,
                                            2,
                                            0.9,
                                            "Reuniao A [[PERSON_NAME_1]] fala da primeira"),
                                    new SplitDtos.SegmentDto(
                                            2,
                                            "Reuniao B",
                                            3,
                                            totalLines,
                                            0.85,
                                            "Reuniao B fala da segunda")),
                            totalLines,
                            new SplitDtos.SplitMetadata(
                                    "stub-split", "meeting-split-v1", 0, 0, 1, 0));
                }
            };
        }
    }
}
