package br.com.nora.api.application.platform;

import java.util.Map;

/** Operator audit port (platform_audit_log table, ADR 0023). */
public interface PlatformAuditRepository {

    /**
     * Records an operator mutation. {@code operatorEmail} comes from the X-Operator-Email header
     * (forwarded by nora-admin from Easy Auth); it may be null/blank.
     */
    void record(
            String operatorEmail,
            String action,
            String targetType,
            String targetId,
            Map<String, Object> detail);
}
