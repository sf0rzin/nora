package br.com.nora.api.api.dto.iam;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Body of {@code PUT /iam/policies/{id}} — the document only; name and description are set at
 * creation. Cardinality of the document is capped by {@code PolicyDocument} at parse time.
 */
public record UpdatePolicyRequest(JsonNode document) {}
