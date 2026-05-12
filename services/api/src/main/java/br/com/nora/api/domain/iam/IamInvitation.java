package br.com.nora.api.domain.iam;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Convite de usuario para um tenant (US06, ADR 0011).
 *
 * <p>Agregado imutavel — toda transicao de estado retorna nova instancia. As regras de negocio
 * ficam aqui: state transitions sao validas apenas quando o status atual permite, e a expiracao e
 * determinada pelo tempo do relogio fornecido pela camada de aplicacao.
 *
 * <p>O {@code token} e tratado como secret: nunca deve ser devolvido em respostas de listagem e
 * jamais logado em producao. Ele e a credencial do convidado para o endpoint publico de aceite.
 *
 * <p>{@code groupIds} pode estar vazio quando o invite nao anexa o user a nenhum grupo no aceite.
 * {@code acceptedAt} e {@code acceptedUserId} sao preenchidos apenas no aceite.
 */
public record IamInvitation(
        UUID id,
        UUID tenantId,
        String email,
        String token,
        InvitationStatus status,
        UUID invitedBy,
        Instant invitedAt,
        Instant expiresAt,
        Instant acceptedAt,
        UUID acceptedUserId,
        Set<UUID> groupIds) {

    public IamInvitation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(invitedBy, "invitedBy");
        Objects.requireNonNull(invitedAt, "invitedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        groupIds = groupIds == null ? Set.of() : Set.copyOf(groupIds);
    }

    /** Indica se o convite ja passou do prazo de aceite. */
    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /**
     * Marca o convite como aceito retornando uma nova instancia. So permitido a partir de {@code
     * PENDING}.
     */
    public IamInvitation accept(UUID userId, Instant acceptedAt) {
        if (status != InvitationStatus.PENDING) {
            throw new IllegalStateException(
                    "cannot accept invite from status " + status + "; expected PENDING");
        }
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        return new IamInvitation(
                id,
                tenantId,
                email,
                token,
                InvitationStatus.ACCEPTED,
                invitedBy,
                invitedAt,
                expiresAt,
                acceptedAt,
                userId,
                groupIds);
    }

    /**
     * Marca o convite como revogado retornando uma nova instancia. So permitido a partir de {@code
     * PENDING} — revogacao apos aceite/expiracao e no-op semantica e fica como erro de aplicacao
     * para clarificar intencao.
     */
    public IamInvitation revoke() {
        if (status != InvitationStatus.PENDING) {
            throw new IllegalStateException(
                    "cannot revoke invite from status " + status + "; expected PENDING");
        }
        return new IamInvitation(
                id,
                tenantId,
                email,
                token,
                InvitationStatus.REVOKED,
                invitedBy,
                invitedAt,
                expiresAt,
                acceptedAt,
                acceptedUserId,
                groupIds);
    }

    /**
     * Marca o convite como expirado retornando uma nova instancia. Usado no on-read expire (lazy)
     * antes de listar/aceitar. So tem efeito a partir de {@code PENDING}.
     */
    public IamInvitation markExpired() {
        if (status != InvitationStatus.PENDING) {
            throw new IllegalStateException(
                    "cannot expire invite from status " + status + "; expected PENDING");
        }
        return new IamInvitation(
                id,
                tenantId,
                email,
                token,
                InvitationStatus.EXPIRED,
                invitedBy,
                invitedAt,
                expiresAt,
                acceptedAt,
                acceptedUserId,
                groupIds);
    }
}
