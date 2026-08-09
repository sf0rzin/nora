package br.com.nora.api.application.tenant;

import br.com.nora.api.application.ports.TenantContextRepository;
import br.com.nora.api.domain.tenant.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Service for the tenant's commercial context (US14/US30). 1-1 with tenants. */
@Service
public class TenantContextService {

    private final TenantContextRepository repo;

    public TenantContextService(TenantContextRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Optional<TenantContext> find(UUID tenantId) {
        return repo.findByTenantId(tenantId);
    }

    @Transactional
    public TenantContext upsert(UUID tenantId, UpsertCommand cmd, UUID actor) {
        if (cmd == null) {
            throw new TenantContextException.Invalid("payload is required");
        }
        if (cmd.companyName() == null || cmd.companyName().isBlank()) {
            throw new TenantContextException.Invalid("companyName is required");
        }
        Optional<TenantContext> existing = repo.findByTenantId(tenantId);
        TenantContext toSave;
        if (existing.isPresent()) {
            toSave =
                    existing.get()
                            .withUpdates(
                                    cmd.companyName(),
                                    cmd.industry(),
                                    cmd.valueProposition(),
                                    cmd.idealCustomerProfile(),
                                    toProducts(cmd.products()),
                                    nullSafe(cmd.competitors()),
                                    nullSafe(cmd.objectionHandling()),
                                    actor);
        } else {
            toSave =
                    TenantContext.newContext(
                            tenantId,
                            cmd.companyName(),
                            cmd.industry(),
                            cmd.valueProposition(),
                            cmd.idealCustomerProfile(),
                            toProducts(cmd.products()),
                            nullSafe(cmd.competitors()),
                            nullSafe(cmd.objectionHandling()),
                            actor);
        }
        return repo.save(toSave);
    }

    private static List<String> nullSafe(List<String> in) {
        return in == null ? List.of() : in.stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    private static List<TenantContext.Product> toProducts(List<ProductInput> in) {
        if (in == null) {
            return List.of();
        }
        return in.stream()
                .filter(p -> p != null && p.name() != null && !p.name().isBlank())
                .map(
                        p ->
                                new TenantContext.Product(
                                        p.name(),
                                        p.description(),
                                        nullSafe(p.keyDifferentiators())))
                .toList();
    }

    /** Upsert command (optional fields accept null). */
    public record UpsertCommand(
            String companyName,
            String industry,
            String valueProposition,
            String idealCustomerProfile,
            List<ProductInput> products,
            List<String> competitors,
            List<String> objectionHandling) {}

    public record ProductInput(String name, String description, List<String> keyDifferentiators) {}
}
