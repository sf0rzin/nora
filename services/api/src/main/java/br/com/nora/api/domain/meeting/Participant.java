package br.com.nora.api.domain.meeting;

/** Participant listed by the user in the meeting upload. */
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
