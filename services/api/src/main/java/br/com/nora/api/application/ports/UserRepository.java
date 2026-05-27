package br.com.nora.api.application.ports;

import br.com.nora.api.domain.identity.Email;
import br.com.nora.api.domain.identity.User;
import br.com.nora.api.domain.identity.UserStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistencia para o agregado User. */
public interface UserRepository {

    Optional<User> findById(UUID id);

    Optional<User> findByEmail(Email email);

    User save(User user);

    /** Marca o usuario como Root do tenant. So pode existir um Root ativo por tenant. */
    void markAsRoot(UUID userId, UUID tenantId);

    /** Indica se o usuario e o Root do tenant. */
    boolean isRoot(UUID userId, UUID tenantId);

    /** Lista (projecao leve) os usuarios ativos de um tenant, ordenados por criacao. */
    List<TenantUserSummary> listByTenant(UUID tenantId);

    /** Projecao de leitura de um usuario do tenant (sem senha/hash) para gestao IAM. */
    record TenantUserSummary(
            UUID id,
            String email,
            String displayName,
            boolean isRoot,
            UserStatus status,
            Instant createdAt) {}
}
