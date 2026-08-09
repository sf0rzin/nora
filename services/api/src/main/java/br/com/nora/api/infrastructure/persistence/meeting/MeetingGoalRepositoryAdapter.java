package br.com.nora.api.infrastructure.persistence.meeting;

import br.com.nora.api.application.ports.MeetingGoalRepository;
import br.com.nora.api.domain.meeting.productivity.ExpectedOutcome;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MeetingGoalRepositoryAdapter implements MeetingGoalRepository {

    private final MeetingGoalJpaRepository jpa;

    public MeetingGoalRepositoryAdapter(MeetingGoalJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public MeetingGoal save(MeetingGoal goal) {
        // Idempotent upsert: if a goal already exists for the meeting/tenant, we remove it first
        // via
        // native DELETE. Postgres' ON DELETE CASCADE clears the outcomes without the problematic
        // UPDATE SET NULL cycle Hibernate would do with orphanRemoval+unidirectional @JoinColumn.
        jpa.deleteByMeetingIdAndTenantIdNative(goal.meetingId(), goal.tenantId());
        jpa.flush();
        MeetingGoalJpaEntity entity = toEntity(goal);
        MeetingGoalJpaEntity saved = jpa.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MeetingGoal> findByMeetingId(UUID meetingId, UUID tenantId) {
        return jpa.findByMeetingIdAndTenantId(meetingId, tenantId).map(this::toDomain);
    }

    @Override
    @Transactional
    public void deleteByMeetingId(UUID meetingId, UUID tenantId) {
        // Native DELETE triggers ON DELETE CASCADE over the outcomes; avoids the UPDATE SET NULL of
        // Hibernate's default cycle.
        jpa.deleteByMeetingIdAndTenantIdNative(meetingId, tenantId);
    }

    private MeetingGoalJpaEntity toEntity(MeetingGoal goal) {
        MeetingGoalJpaEntity e = new MeetingGoalJpaEntity();
        e.setId(goal.id());
        e.setTenantId(goal.tenantId());
        e.setMeetingId(goal.meetingId());
        e.setPurpose(goal.purpose());
        e.setProjectStateSnapshot(goal.projectStateSnapshot());
        e.setCreatedAt(goal.createdAt());
        e.setUpdatedAt(goal.updatedAt());
        for (ExpectedOutcome outcome : goal.expectedOutcomes()) {
            ExpectedOutcomeJpaEntity oe = new ExpectedOutcomeJpaEntity();
            oe.setId(UUID.randomUUID());
            oe.setMeetingGoalId(goal.id());
            oe.setOutcomeText(outcome.text());
            oe.setPosition(outcome.position());
            e.getExpectedOutcomes().add(oe);
        }
        return e;
    }

    private MeetingGoal toDomain(MeetingGoalJpaEntity e) {
        List<ExpectedOutcome> outcomes =
                e.getExpectedOutcomes().stream()
                        .map(oe -> new ExpectedOutcome(oe.getOutcomeText(), oe.getPosition()))
                        .toList();
        return new MeetingGoal(
                e.getId(),
                e.getTenantId(),
                e.getMeetingId(),
                e.getPurpose(),
                outcomes,
                e.getProjectStateSnapshot(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
