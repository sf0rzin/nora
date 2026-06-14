package br.com.nora.api.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declara a permissão IAM exigida por um handler de controller (ADR 0007, #51).
 *
 * <p>O {@code RequiresPermissionInterceptor} resolve o principal autenticado, monta o ARN do
 * recurso (via {@link ResourceArns}) a partir do tenant + um {@code @PathVariable} opcional, e
 * chama o {@code AuthorizationService} ANTES do método — substituindo o {@code authz.require(...)}
 * manual nos endpoints SEM conditions por-atributo.
 *
 * <p><b>Limite deliberado:</b> endpoints cujo Allow depende de ATRIBUTOS do recurso (ex.: {@code
 * meeting.attributes()} no get/update/reprocess) continuam autorizando explícito no corpo — o
 * interceptor roda antes de carregar o recurso, então não tem como alimentar as conditions. Esses
 * casos ficam com {@code authz.require(..., attributes)} dentro da transação (TOCTOU-safe).
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequiresPermission {

    /** Ação IAM exigida, ex.: {@code "meeting:read"}. */
    String action();

    /** Tipo de recurso para montar o ARN. */
    ResourceType resource();

    /** Nome do {@code @PathVariable} que traz o id do recurso; vazio = wildcard ({@code *}). */
    String idParam() default "";

    /**
     * Pre-check sem conditions (usa {@code requireAnyAllow}): garante ao menos um Allow para
     * action+resource ignorando conditions. Para list-endpoints onde o filtro fino é por item.
     */
    boolean anyAllow() default false;

    /** Tipo de recurso IAM — mapeado para o ARN por {@link ResourceArns#of}. */
    enum ResourceType {
        MEETING,
        TASK,
        INVITE,
        IAM,
        TENANT,
        TENANT_CONTEXT
    }
}
