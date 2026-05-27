package br.com.nora.api.api.dto.iam;

public record SimulateResponse(
        java.util.UUID userId, String action, String resource, boolean allowed) {}
