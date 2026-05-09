package br.com.nora.api.api.dto.iam;

import com.fasterxml.jackson.databind.JsonNode;

public record UpdatePolicyRequest(JsonNode document) {}
