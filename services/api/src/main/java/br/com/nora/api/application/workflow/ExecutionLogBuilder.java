package br.com.nora.api.application.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Acumula as entradas do log de uma execução de workflow e serializa para o {@code log_json} (array
 * de {at, nodeId, level, message}) que a UI de histórico renderiza. Não thread-safe — uma instância
 * por execução.
 */
public final class ExecutionLogBuilder {

    /** Uma linha do log. {@code level}: info | error. {@code nodeId} é nulo em linhas gerais. */
    public record Entry(String at, String nodeId, String level, String message) {}

    private final List<Entry> entries = new ArrayList<>();

    public void info(String nodeId, String message) {
        entries.add(new Entry(Instant.now().toString(), nodeId, "info", message));
    }

    public void error(String nodeId, String message) {
        entries.add(new Entry(Instant.now().toString(), nodeId, "error", message));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public String toJson(ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(entries);
        } catch (Exception ex) {
            // Nunca derruba a finalização da execução por causa do log.
            return "[]";
        }
    }
}
