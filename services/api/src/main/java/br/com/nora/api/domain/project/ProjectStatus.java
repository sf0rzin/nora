package br.com.nora.api.domain.project;

/** Estado de um projeto. Espelha o CHECK constraint projects_status_chk (V019). */
public enum ProjectStatus {
    ACTIVE,
    ARCHIVED;

    public static ProjectStatus fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        return ProjectStatus.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
