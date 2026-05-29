package br.com.nora.api.application.platform;

import java.util.Map;

/** Porta de auditoria do operador (tabela platform_audit_log, ADR 0023). */
public interface PlatformAuditRepository {

    /**
     * Registra uma mutação do operador. {@code operatorEmail} vem do header X-Operator-Email
     * (repassado pelo nora-admin a partir do Easy Auth); pode ser null/blank.
     */
    void record(
            String operatorEmail,
            String action,
            String targetType,
            String targetId,
            Map<String, Object> detail);
}
