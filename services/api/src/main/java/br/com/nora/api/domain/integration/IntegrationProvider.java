package br.com.nora.api.domain.integration;

/** Provedores externos suportados pelo hub de integrações do NORA Flows. */
public enum IntegrationProvider {
    GOOGLE("google"),
    SLACK("slack"),
    GITHUB("github"),
    NOTION("notion"),
    TODOIST("todoist"),
    LINEAR("linear"),
    MICROSOFT("microsoft"),
    TELEGRAM("telegram"),
    TRELLO("trello");

    private final String wire;

    IntegrationProvider(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static IntegrationProvider fromWire(String raw) {
        if (raw != null) {
            for (IntegrationProvider p : values()) {
                if (p.wire.equalsIgnoreCase(raw.trim())) {
                    return p;
                }
            }
        }
        throw new IllegalArgumentException("unknown integration provider: " + raw);
    }
}
