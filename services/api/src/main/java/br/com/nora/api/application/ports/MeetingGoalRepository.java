package br.com.nora.api.application.ports;

import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import java.util.Optional;
import java.util.UUID;

/** Persistencia do objetivo declarado (MeetingGoal). Toda consulta exige tenantId. */
public interface MeetingGoalRepository {

    MeetingGoal save(MeetingGoal goal);

    Optional<MeetingGoal> findByMeetingId(UUID meetingId, UUID tenantId);

    void deleteByMeetingId(UUID meetingId, UUID tenantId);
}
