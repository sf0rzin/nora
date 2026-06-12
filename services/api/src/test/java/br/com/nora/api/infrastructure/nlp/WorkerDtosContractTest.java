package br.com.nora.api.infrastructure.nlp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Trava o contrato JSON enviado ao NLP worker. O worker valida com Pydantic {@code extra="forbid"}
 * (services/nlp-worker/src/nora_nlp/models.py) — qualquer chave fora da lista derruba a análise com
 * 422 em produção. Foi exatamente o que aconteceu quando a API enviava "commercialPlaybook" e
 * "keyFeatures" para um worker que só conhece "objectionHandling" e "keyDifferentiators".
 */
class WorkerDtosContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // Campos aceitos por TenantContext no worker (models.py, aliases camelCase).
    private static final Set<String> WORKER_TENANT_CONTEXT_FIELDS =
            Set.of(
                    "companyName",
                    "industry",
                    "valueProposition",
                    "products",
                    "competitors",
                    "idealCustomerProfile",
                    "objectionHandling",
                    "glossary");

    // Campos aceitos por TenantProduct no worker.
    private static final Set<String> WORKER_PRODUCT_FIELDS =
            Set.of("name", "description", "keyDifferentiators");

    @Test
    void tenantContext_serializaApenasChavesQueOWorkerAceita() {
        WorkerDtos.TenantContext ctx =
                new WorkerDtos.TenantContext(
                        "Acme",
                        "SaaS",
                        "vendemos",
                        List.of(new WorkerDtos.Product("NORA", "plataforma", List.of("flows"))),
                        List.of("Rival"),
                        "PMEs",
                        List.of("preço alto -> ROI"),
                        List.of(new WorkerDtos.GlossaryEntry("MQL", "lead qualificado")));

        JsonNode json = MAPPER.valueToTree(ctx);

        assertThat(fieldNames(json)).isSubsetOf(WORKER_TENANT_CONTEXT_FIELDS);
        assertThat(json.has("objectionHandling")).isTrue();
        assertThat(fieldNames(json.path("products").get(0))).isSubsetOf(WORKER_PRODUCT_FIELDS);
        assertThat(json.path("products").get(0).has("keyDifferentiators")).isTrue();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Iterator<String> it = node.fieldNames();
        java.util.HashSet<String> names = new java.util.HashSet<>();
        it.forEachRemaining(names::add);
        return names;
    }
}
