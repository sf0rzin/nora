package br.com.nora.api.application.tenant;

import br.com.nora.api.application.ports.TenantContextRepository;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.domain.tenant.TenantContextVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for the tenant's commercial context (US14/US30) and for its history (US31). 1-1 with
 * tenants.
 *
 * <p>There is no restore endpoint, and that is a decision rather than an omission: a version is
 * restored by loading it into the editor and saving, which goes back through {@link #upsert} and
 * therefore produces version N+1 with the real actor and timestamp on it. A dedicated rollback
 * handler would be a second write path into the same aggregate whose only difference is where the
 * payload came from, and it would have to answer "does rolling back to 3 create 6, or resurrect 3?"
 * — the first is what upsert already does, the second breaks the append-only invariant the table
 * exists to hold.
 */
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

    /** History of the tenant's context, newest first. Empty when nothing was ever saved. */
    @Transactional(readOnly = true)
    public List<TenantContextVersion> listVersions(UUID tenantId) {
        return repo.listVersions(tenantId);
    }

    /**
     * One version with the document it froze.
     *
     * @throws TenantContextException.VersionNotFound when the tenant has no such version — which is
     *     also the answer for a version number that exists under a DIFFERENT tenant, because the
     *     lookup is scoped by tenant before it is scoped by number.
     */
    @Transactional(readOnly = true)
    public TenantContextVersion.Detail findVersion(UUID tenantId, int version) {
        if (version < 1) {
            throw new TenantContextException.VersionNotFound(version);
        }
        return repo.findVersion(tenantId, version)
                .orElseThrow(() -> new TenantContextException.VersionNotFound(version));
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
