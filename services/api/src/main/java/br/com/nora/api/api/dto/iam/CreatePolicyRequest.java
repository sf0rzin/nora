package br.com.nora.api.api.dto.iam;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /iam/policies}.
 *
 * <p>The bounds are here rather than only in the handler because this record is what the request
 * body BECOMES: a name of a megabyte is already in the heap by the time a manual {@code isBlank}
 * check runs, and the same free-text fields are echoed back by every listing. The document itself
 * is a {@code JsonNode} and cannot be bounded by an annotation — its cardinality caps live in
 * {@code PolicyDocument} / {@code PolicyStatement}, which is where they can be enforced on the
 * parsed shape rather than on the text.
 */
public record CreatePolicyRequest(
        @NotBlank(message = "name is required") @Size(max = 120) String name,
        @Size(max = 500) String description,
        JsonNode document) {}
