package br.com.nora.api.application.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates the log entries of a workflow execution and serializes them to the {@code log_json}
 * (array of {at, nodeId, level, message}) that the history UI renders. Not thread-safe — one
 * instance per execution.
 */
public final class ExecutionLogBuilder {

    /** One log line. {@code level}: info | error. {@code nodeId} is null on general lines. */
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
            // Never bring down the execution's finalization because of the log.
            return "[]";
        }
    }
}
