package br.com.nora.api.domain.platform;

/**
 * Modality supported by a catalog model (ADR 0024). The per-service router uses this: a {@code
 * multimodal} service can only bind a {@code MULTIMODAL} model.
 */
public enum Modality {
    TEXT,
    MULTIMODAL;

    public static Modality fromWire(String raw) {
        if (raw == null) {
            return TEXT;
        }
        return switch (raw.trim().toLowerCase()) {
            case "multimodal" -> MULTIMODAL;
            default -> TEXT;
        };
    }

    public String wire() {
        return name().toLowerCase();
    }
}
