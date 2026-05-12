package br.com.nora.api.domain.iam;

/**
 * Status do convite de usuario (US06, ADR 0011).
 *
 * <ul>
 *   <li>{@code PENDING} — criado, ainda dentro do prazo, nao revogado.
 *   <li>{@code ACCEPTED} — aceito; usuario foi criado e anexado aos grupos.
 *   <li>{@code EXPIRED} — passou de expiresAt sem aceite.
 *   <li>{@code REVOKED} — cancelado por quem tem permissao IAM antes do aceite.
 * </ul>
 */
public enum InvitationStatus {
    PENDING,
    ACCEPTED,
    EXPIRED,
    REVOKED
}
