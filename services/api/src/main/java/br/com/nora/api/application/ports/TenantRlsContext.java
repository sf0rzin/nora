package br.com.nora.api.application.ports;

import java.util.UUID;

/**
 * Permite a camada de aplicação propagar EXPLICITAMENTE o tenant para o mecanismo de RLS (o GUC
 * {@code nora.current_tenant_id} que o {@code TenantRlsAspect} aplica por transação) em código que
 * roda FORA de uma thread de request HTTP — onde o {@code TenantContextHolder} não foi populado
 * pelo filtro de autenticação.
 *
 * <p>Caso de uso (ADR 0028): o pipeline de análise roda async numa thread de executor. Sob RLS
 * enforce, suas escritas em tabelas enforced ({@code meeting_analyses} + filhos) precisam do GUC —
 * mas a thread async não herda o {@code TenantContextHolder} do request. O serviço, que recebe o
 * {@code tenantId}, chama {@link #set(UUID)} no início e {@link #clear()} no finally.
 *
 * <p>O adapter na infraestrutura delega para o mesmo holder que o aspect lê — mantendo a regra DDD
 * (application não conhece infraestrutura) via porta.
 */
public interface TenantRlsContext {

    /** Associa o tenant à thread corrente, para que o RLS aspect o aplique nas transações dela. */
    void set(UUID tenantId);

    /** Limpa o tenant da thread corrente. Chamar no finally (threads de pool são reusadas). */
    void clear();
}
