package br.com.nora.api.application.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.ports.TenantContextRepository;
import br.com.nora.api.application.tenant.TenantContextService.UpsertCommand;
import br.com.nora.api.domain.tenant.TenantContext;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests do TenantContextService: upsert + versionamento (US31). */
class TenantContextServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private InMemoryTenantContextRepo repo;
    private TenantContextService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryTenantContextRepo();
        service = new TenantContextService(repo);
    }

    private static UpsertCommand cmd(String companyName) {
        return new UpsertCommand(companyName, null, null, null, null, null, null);
    }

    @Test
    void firstUpsertCreatesContextAtVersionOne() {
        TenantContext created = service.upsert(tenantId, cmd("Acme"), actor);

        assertThat(created.version()).isEqualTo(1);
        assertThat(created.companyName()).isEqualTo("Acme");
        assertThat(created.tenantId()).isEqualTo(tenantId);
    }

    @Test
    void secondUpsertIncrementsVersionAndPreservesIdentity() {
        TenantContext first = service.upsert(tenantId, cmd("Acme"), actor);
        TenantContext second = service.upsert(tenantId, cmd("Acme Corp"), actor);

        assertThat(second.version()).isEqualTo(2);
        // mesma identidade (mesmo agregado, createdAt preservado), conteudo atualizado.
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.createdAt()).isEqualTo(first.createdAt());
        assertThat(second.companyName()).isEqualTo("Acme Corp");

        // estado persistido reflete a ultima versao.
        assertThat(service.find(tenantId)).get().extracting(TenantContext::version).isEqualTo(2);
    }

    @Test
    void thirdUpsertKeepsClimbing() {
        service.upsert(tenantId, cmd("Acme"), actor);
        service.upsert(tenantId, cmd("Acme"), actor);
        TenantContext third = service.upsert(tenantId, cmd("Acme"), actor);

        assertThat(third.version()).isEqualTo(3);
    }

    @Test
    void upsertRejectsBlankCompanyName() {
        assertThatThrownBy(() -> service.upsert(tenantId, cmd("   "), actor))
                .isInstanceOf(TenantContextException.Invalid.class);
    }

    // ============================================================
    // Fake — espelha a semantica do adapter real: o save de upsert
    // preserva o id existente por tenant (nao cria linha nova).
    // ============================================================
    private static class InMemoryTenantContextRepo implements TenantContextRepository {
        private final Map<UUID, TenantContext> store = new HashMap<>();

        @Override
        public TenantContext save(TenantContext context) {
            store.put(context.tenantId(), context);
            return context;
        }

        @Override
        public Optional<TenantContext> findByTenantId(UUID tenantId) {
            return Optional.ofNullable(store.get(tenantId));
        }
    }
}
