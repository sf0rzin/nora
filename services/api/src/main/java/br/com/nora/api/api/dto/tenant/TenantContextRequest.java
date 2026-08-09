package br.com.nora.api.api.dto.tenant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Upsert payload for the tenant context. */
public record TenantContextRequest(
        @NotBlank @Size(max = 200) String companyName,
        @Size(max = 200) String industry,
        @Size(max = 2000) String valueProposition,
        @Size(max = 2000) String idealCustomerProfile,
        List<ProductPayload> products,
        List<@Size(max = 200) String> competitors,
        List<@Size(max = 500) String> objectionHandling) {

    public record ProductPayload(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,
            List<@Size(max = 200) String> keyDifferentiators) {}
}
