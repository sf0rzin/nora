package br.com.nora.api.api.dto.meeting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Create/update payload for the MeetingGoal (opt-in input of the Productivity Score, ADR 0005).
 *
 * <p>Validations mirror the limits of the MeetingGoal/ExpectedOutcome domain.
 */
public record MeetingGoalRequest(
        @NotBlank(message = "purpose is required")
                @Size(min = 3, max = 500, message = "purpose length must be between 3 and 500")
                String purpose,
        @NotEmpty(message = "expectedOutcomes must have at least one item")
                @Size(
                        min = 1,
                        max = 10,
                        message = "expectedOutcomes must have between 1 and 10 items")
                List<
                                @NotBlank(message = "outcome is required")
                                @Size(
                                        min = 3,
                                        max = 240,
                                        message = "outcome length must be between 3 and 240")
                                String>
                        expectedOutcomes,
        @Size(max = 2000, message = "projectStateSnapshot must be at most 2000 chars")
                String projectStateSnapshot) {}
