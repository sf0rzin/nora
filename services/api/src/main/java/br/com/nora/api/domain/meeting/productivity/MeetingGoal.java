package br.com.nora.api.domain.meeting.productivity;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Objetivo declarado pelo usuario para uma reuniao (input opt-in do Productivity Score, ADR 0005).
 *
 * <p>1-1 com {@code Meeting}: cada meeting tem no maximo um goal ativo. Imutavel.
 */
public final class MeetingGoal {

    private static final int PURPOSE_MIN = 3;
    private static final int PURPOSE_MAX = 500;
    private static final int SNAPSHOT_MAX = 2000;
    private static final int OUTCOMES_MIN = 1;
    private static final int OUTCOMES_MAX = 10;

    private final UUID id;
    private final UUID tenantId;
    private final UUID meetingId;
    private final String purpose;
    private final List<ExpectedOutcome> expectedOutcomes;
    private final String projectStateSnapshot;
    private final OffsetDateTime createdAt;
    private final OffsetDateTime updatedAt;

    public MeetingGoal(
            UUID id,
            UUID tenantId,
            UUID meetingId,
            String purpose,
            List<ExpectedOutcome> expectedOutcomes,
            String projectStateSnapshot,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (meetingId == null) {
            throw new IllegalArgumentException("meetingId is required");
        }
        if (purpose == null || purpose.isBlank()) {
            throw new IllegalArgumentException("purpose is required");
        }
        String trimmedPurpose = purpose.trim();
        if (trimmedPurpose.length() < PURPOSE_MIN || trimmedPurpose.length() > PURPOSE_MAX) {
            throw new IllegalArgumentException(
                    "purpose length must be between " + PURPOSE_MIN + " and " + PURPOSE_MAX);
        }
        if (expectedOutcomes == null || expectedOutcomes.isEmpty()) {
            throw new IllegalArgumentException("at least one expected outcome is required");
        }
        if (expectedOutcomes.size() < OUTCOMES_MIN || expectedOutcomes.size() > OUTCOMES_MAX) {
            throw new IllegalArgumentException(
                    "expected outcomes count must be between "
                            + OUTCOMES_MIN
                            + " and "
                            + OUTCOMES_MAX);
        }
        String trimmedSnapshot = projectStateSnapshot == null ? null : projectStateSnapshot.trim();
        if (trimmedSnapshot != null && trimmedSnapshot.isEmpty()) {
            trimmedSnapshot = null;
        }
        if (trimmedSnapshot != null && trimmedSnapshot.length() > SNAPSHOT_MAX) {
            throw new IllegalArgumentException(
                    "projectStateSnapshot must be at most " + SNAPSHOT_MAX + " chars");
        }
        this.id = id;
        this.tenantId = tenantId;
        this.meetingId = meetingId;
        this.purpose = trimmedPurpose;
        this.expectedOutcomes = Collections.unmodifiableList(new ArrayList<>(expectedOutcomes));
        this.projectStateSnapshot = trimmedSnapshot;
        this.createdAt = createdAt == null ? OffsetDateTime.now() : createdAt;
        this.updatedAt = updatedAt == null ? this.createdAt : updatedAt;
    }

    /**
     * Factory para criar um novo goal a partir de inputs do usuario (textos crus, sem position).
     * Position eh atribuida automaticamente pela ordem da lista.
     */
    public static MeetingGoal create(
            UUID tenantId,
            UUID meetingId,
            String purpose,
            List<String> expectedOutcomesText,
            String projectStateSnapshot) {
        if (expectedOutcomesText == null) {
            throw new IllegalArgumentException("expectedOutcomes is required");
        }
        List<ExpectedOutcome> outcomes = new ArrayList<>(expectedOutcomesText.size());
        for (int i = 0; i < expectedOutcomesText.size(); i++) {
            outcomes.add(new ExpectedOutcome(expectedOutcomesText.get(i), i));
        }
        OffsetDateTime now = OffsetDateTime.now();
        return new MeetingGoal(
                UUID.randomUUID(),
                tenantId,
                meetingId,
                purpose,
                outcomes,
                projectStateSnapshot,
                now,
                now);
    }

    /** Devolve uma copia atualizada mantendo o id (para PUT idempotente). */
    public MeetingGoal updated(
            String newPurpose,
            List<String> newExpectedOutcomesText,
            String newProjectStateSnapshot) {
        List<ExpectedOutcome> outcomes = new ArrayList<>(newExpectedOutcomesText.size());
        for (int i = 0; i < newExpectedOutcomesText.size(); i++) {
            outcomes.add(new ExpectedOutcome(newExpectedOutcomesText.get(i), i));
        }
        return new MeetingGoal(
                this.id,
                this.tenantId,
                this.meetingId,
                newPurpose,
                outcomes,
                newProjectStateSnapshot,
                this.createdAt,
                OffsetDateTime.now());
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID meetingId() {
        return meetingId;
    }

    public String purpose() {
        return purpose;
    }

    public List<ExpectedOutcome> expectedOutcomes() {
        return expectedOutcomes;
    }

    public String projectStateSnapshot() {
        return projectStateSnapshot;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    public OffsetDateTime updatedAt() {
        return updatedAt;
    }
}
