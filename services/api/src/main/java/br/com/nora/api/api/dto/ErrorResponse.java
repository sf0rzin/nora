package br.com.nora.api.api.dto;

import java.time.Instant;
import java.util.List;

/** Standard API error format. Mirrors docs/api/examples/error.example.json. */
public record ErrorResponse(
        String code, String message, String traceId, Instant timestamp, List<FieldIssue> details) {

    public record FieldIssue(String field, String issue) {}
}
