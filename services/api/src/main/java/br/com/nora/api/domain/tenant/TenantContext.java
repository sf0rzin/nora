package br.com.nora.api.domain.tenant;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Contexto comercial do tenant (US14/US30) usado para enriquecer o prompt da analise. Imutavel.
 *
 * <p>Campos opcionais sao tratados como vazio quando ausentes; o agregado nunca expoe nulls em
 * colecoes.
 */
public final class TenantContext {

    private final UUID id;
    private final UUID tenantId;
    private final String companyName;
    private final String industry;
    private final String valueProposition;
    private final String idealCustomerProfile;
    private final List<Product> products;
    private final List<String> competitors;
    private final List<String> objectionHandling;
    private final UUID updatedBy;
    private final int version;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public TenantContext(
            UUID id,
            UUID tenantId,
            String companyName,
            String industry,
            String valueProposition,
            String idealCustomerProfile,
            List<Product> products,
            List<String> competitors,
            List<String> objectionHandling,
            UUID updatedBy,
            int version,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (companyName == null || companyName.isBlank()) {
            throw new IllegalArgumentException("companyName is required");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        this.version = version;
        this.id = id;
        this.tenantId = tenantId;
        this.companyName = companyName.trim();
        this.industry = nullIfBlank(industry);
        this.valueProposition = nullIfBlank(valueProposition);
        this.idealCustomerProfile = nullIfBlank(idealCustomerProfile);
        this.products = freeze(products);
        this.competitors = freeze(competitors);
        this.objectionHandling = freeze(objectionHandling);
        this.updatedBy = updatedBy;
        this.createdAt = createdAt == null ? OffsetDateTime.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    public static TenantContext newContext(
            UUID tenantId,
            String companyName,
            String industry,
            String valueProposition,
            String idealCustomerProfile,
            List<Product> products,
            List<String> competitors,
            List<String> objectionHandling,
            UUID updatedBy) {
        OffsetDateTime now = OffsetDateTime.now();
        return new TenantContext(
                UUID.randomUUID(),
                tenantId,
                companyName,
                industry,
                valueProposition,
                idealCustomerProfile,
                products,
                competitors,
                objectionHandling,
                updatedBy,
                1,
                now,
                now);
    }

    /**
     * Cria nova instancia com mesmos id/tenant/createdAt mas conteudo atualizado. A cada update o
     * contador de versao (US31) sobe 1.
     */
    public TenantContext withUpdates(
            String companyName,
            String industry,
            String valueProposition,
            String idealCustomerProfile,
            List<Product> products,
            List<String> competitors,
            List<String> objectionHandling,
            UUID updatedBy) {
        return new TenantContext(
                this.id,
                this.tenantId,
                companyName,
                industry,
                valueProposition,
                idealCustomerProfile,
                products,
                competitors,
                objectionHandling,
                updatedBy,
                this.version + 1,
                this.createdAt,
                OffsetDateTime.now());
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static <T> List<T> freeze(List<T> input) {
        return input == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(input));
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String companyName() {
        return companyName;
    }

    public String industry() {
        return industry;
    }

    public String valueProposition() {
        return valueProposition;
    }

    public String idealCustomerProfile() {
        return idealCustomerProfile;
    }

    public List<Product> products() {
        return products;
    }

    public List<String> competitors() {
        return competitors;
    }

    public List<String> objectionHandling() {
        return objectionHandling;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    public int version() {
        return version;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }

    /** Produto comercializado pelo tenant. Usado pelo worker para alimentar o prompt. */
    public record Product(String name, String description, List<String> keyDifferentiators) {

        public Product {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("product name is required");
            }
            keyDifferentiators =
                    keyDifferentiators == null ? List.of() : List.copyOf(keyDifferentiators);
        }
    }
}
