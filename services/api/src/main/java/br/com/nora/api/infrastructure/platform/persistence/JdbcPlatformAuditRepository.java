package br.com.nora.api.infrastructure.platform.persistence;

import br.com.nora.api.application.platform.PlatformAuditRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Adapter JdbcTemplate de auditoria do operador (tabela platform_audit_log, ADR 0023). */
@Repository
@ConditionalOnProperty(name = "nora.platform.enabled", havingValue = "true")
public class JdbcPlatformAuditRepository implements PlatformAuditRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcPlatformAuditRepository(
            NamedParameterJdbcTemplate platformJdbcTemplate, ObjectMapper json) {
        this.jdbc = platformJdbcTemplate;
        this.json = json;
    }

    @Override
    public void record(
            String operatorEmail,
            String action,
            String targetType,
            String targetId,
            Map<String, Object> detail) {
        String detailJson;
        try {
            detailJson = json.writeValueAsString(detail == null ? Map.of() : detail);
        } catch (JsonProcessingException ex) {
            detailJson = "{}";
        }
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("email", operatorEmail)
                        .addValue("action", action)
                        .addValue("type", targetType)
                        .addValue("target", targetId)
                        .addValue("detail", detailJson);
        jdbc.update(
                "INSERT INTO platform_audit_log (operator_email, action, target_type, target_id,"
                        + " detail) VALUES (:email, :action, :type, :target, CAST(:detail AS jsonb))",
                params);
    }
}
