package br.com.nora.api.domain.platform;

/**
 * Modalidade suportada por um modelo do catálogo (ADR 0024). O router por serviço usa isso: serviço
 * {@code multimodal} só pode bindar um modelo {@code MULTIMODAL}.
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
