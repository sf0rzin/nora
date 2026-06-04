package br.com.nora.api.application.ports;

import br.com.nora.api.domain.iam.IamInvitation;
import br.com.nora.api.domain.iam.InvitationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Porta de persistencia dos convites de usuario (US06). Todas as operacoes sao escopadas por
 * tenant. Persistimos apenas o SHA-256 do token (coluna {@code token_hash}, indexada); o token cru
 * nunca chega aqui.
 */
public interface InvitationRepository {

    /** Busca convite pelo SHA-256 do token (fluxo de aceite). Lookup O(1) por indice. */
    Optional<IamInvitation> findByTokenHash(String tokenHash);

    /** Busca convite por id dentro do tenant (revogacao, lookup administrativo). */
    Optional<IamInvitation> findById(UUID invitationId, UUID tenantId);

    /**
     * Retorna o convite PENDING ativo para o e-mail no tenant, se existir. Usado para idempotencia:
     * evitar criar dois convites pendentes simultaneos para o mesmo destinatario.
     */
    Optional<IamInvitation> findPendingByEmail(UUID tenantId, String email);

    /** Persiste o agregado (insert ou update). Retorna a instancia salva. */
    IamInvitation save(IamInvitation invitation);

    /**
     * Lista convites do tenant. Quando {@code status == null} retorna todos. Resultados sao
     * ordenados por {@code invited_at} desc.
     */
    List<IamInvitation> listByTenant(UUID tenantId, InvitationStatus status);
}
