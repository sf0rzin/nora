package br.com.nora.api.domain.meeting;

/** Participante listado pelo usuario no upload da reuniao. */
public record Participant(String displayName, String email, boolean isInternal) {

    public Participant {
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("participant displayName is required");
        }
        displayName = displayName.trim();
        if (email != null) {
            email = email.trim().toLowerCase();
            if (email.isEmpty()) {
                email = null;
            }
        }
    }
}
