package br.com.nora.api.api.security;

import java.util.UUID;

/**
 * Construtores centralizados de ARNs de recurso do IAM ({@code nora:tenant/{tenantId}:...}).
 *
 * <p>Antes cada controller montava a string à mão — {@code meetingResource}/{@code taskResource}
 * eram duplicados byte-a-byte entre controllers. Um drift de formato quebraria silenciosamente a
 * comparação de autorização (ADR 0002), então a construção do ARN vive numa única fonte aqui.
 * Primeiro passo do refactor de IAM (#51): centralizar os ARNs antes de extrair o interceptor.
 */
public final class ResourceArns {

    private ResourceArns() {}

    private static String base(UUID tenantId) {
        return "nora:tenant/" + tenantId;
    }

    /** {@code nora:tenant/{t}:meeting/{id|*}} — {@code *} quando {@code meetingId} é null. */
    public static String meeting(UUID tenantId, UUID meetingId) {
        return base(tenantId) + ":meeting/" + (meetingId == null ? "*" : meetingId);
    }

    /** {@code nora:tenant/{t}:task/{id|*}} — {@code *} quando {@code taskId} é null. */
    public static String task(UUID tenantId, UUID taskId) {
        return base(tenantId) + ":task/" + (taskId == null ? "*" : taskId);
    }

    /** {@code nora:tenant/{t}:invite/{id|*}} — {@code *} quando {@code inviteId} é null. */
    public static String invite(UUID tenantId, UUID inviteId) {
        return base(tenantId) + ":invite/" + (inviteId == null ? "*" : inviteId);
    }

    /** {@code nora:tenant/{t}:iam/*} — escopo IAM do tenant. */
    public static String iamWildcard(UUID tenantId) {
        return base(tenantId) + ":iam/*";
    }

    /** {@code nora:tenant/{t}} — o próprio tenant. */
    public static String tenant(UUID tenantId) {
        return base(tenantId);
    }

    /** {@code nora:tenant/{t}:tenant/context} — contexto/configuração do tenant. */
    public static String tenantContext(UUID tenantId) {
        return base(tenantId) + ":tenant/context";
    }
}
