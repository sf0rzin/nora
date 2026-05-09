package br.com.nora.api.api.dto.tenant;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TenantContextResponse(
        UUID tenantId,
        String companyName,
        String industry,
        String valueProposition,
        String idealCustomerProfile,
        List<ProductPayload> products,
        List<String> competitors,
        List<String> objectionHandling,
        OffsetDateTime updatedAt) {

    public record ProductPayload(
            String name, String description, List<String> keyDifferentiators) {}
}
