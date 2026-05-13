package br.com.nora.api.infrastructure.persistence.meeting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "meeting_goal_expected_outcomes")
public class ExpectedOutcomeJpaEntity {

    @Id private UUID id;

    @Column(name = "meeting_goal_id", nullable = false)
    private UUID meetingGoalId;

    @Column(name = "outcome_text", nullable = false, columnDefinition = "TEXT")
    private String outcomeText;

    @Column(name = "position", nullable = false)
    private int position;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getMeetingGoalId() {
        return meetingGoalId;
    }

    public void setMeetingGoalId(UUID meetingGoalId) {
        this.meetingGoalId = meetingGoalId;
    }

    public String getOutcomeText() {
        return outcomeText;
    }

    public void setOutcomeText(String outcomeText) {
        this.outcomeText = outcomeText;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }
}
