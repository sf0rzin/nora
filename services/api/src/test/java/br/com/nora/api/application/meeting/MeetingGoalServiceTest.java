package br.com.nora.api.application.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.ports.MeetingGoalRepository;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.ProductivityAssessmentRepository;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.domain.meeting.TranscriptFormat;
import br.com.nora.api.domain.meeting.productivity.CoverageStatus;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.meeting.productivity.OutcomeCoverage;
import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import br.com.nora.api.domain.meeting.productivity.ProductivityBand;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MeetingGoalServiceTest {

    private MeetingGoalService service;
    private InMemoryMeetingRepo meetingRepo;
    private InMemoryGoalRepo goalRepo;
    private InMemoryAssessmentRepo assessmentRepo;

    private final UUID tenant = UUID.randomUUID();
    private final UUID otherTenant = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        meetingRepo = new InMemoryMeetingRepo();
        goalRepo = new InMemoryGoalRepo();
        assessmentRepo = new InMemoryAssessmentRepo();
        service = new MeetingGoalService(meetingRepo, goalRepo, assessmentRepo);
    }

    @Test
    void savePendingMeetingDoesNotReprocess() {
        Meeting m = pendingMeeting(tenant);
        meetingRepo.save(m);

        MeetingGoalService.GoalSaveResult result =
                service.save(
                        m.id(),
                        tenant,
                        "Discovery com lead",
                        List.of("Identificar dor", "Coletar criterios de decisao"),
                        null);

        assertThat(result.goal().purpose()).isEqualTo("Discovery com lead");
        assertThat(result.reprocessTriggered()).isFalse();
        assertThat(meetingRepo.findByIdAndTenant(m.id(), tenant).orElseThrow().processingStatus())
                .isEqualTo(ProcessingStatus.PENDING);
        assertThat(goalRepo.findByMeetingId(m.id(), tenant)).isPresent();
    }

    @Test
    void saveOnCompletedMeetingMarksForReprocessAndClearsAssessment() {
        // Realistic path through the state machine: PENDING -> PROCESSING -> COMPLETED.
        Meeting m =
                pendingMeeting(tenant)
                        .withStatus(ProcessingStatus.PROCESSING)
                        .withStatus(ProcessingStatus.COMPLETED);
        meetingRepo.save(m);
        // Simulates an assessment already persisted from a previous goal.
        assessmentRepo.save(
                ProductivityAssessment.newAssessment(
                        tenant,
                        m.id(),
                        50,
                        ProductivityBand.MEDIUM,
                        List.of(
                                new OutcomeCoverage(
                                        "old outcome", CoverageStatus.ADDRESSED, null, 0)),
                        0.1,
                        0.4,
                        "old rationale"));

        MeetingGoalService.GoalSaveResult result =
                service.save(
                        m.id(), tenant, "Novo objetivo", List.of("outcome novo"), "snapshot novo");

        assertThat(result.reprocessTriggered()).isTrue();
        assertThat(meetingRepo.findByIdAndTenant(m.id(), tenant).orElseThrow().processingStatus())
                .isEqualTo(ProcessingStatus.PENDING);
        assertThat(assessmentRepo.findByMeetingId(m.id(), tenant)).isEmpty();
    }

    @Test
    void updateGoalReplacesOutcomesWithFreshPositions() {
        Meeting m = pendingMeeting(tenant);
        meetingRepo.save(m);

        service.save(
                m.id(),
                tenant,
                "purpose original",
                List.of("primeiro outcome", "segundo outcome", "terceiro outcome"),
                null);
        MeetingGoalService.GoalSaveResult after =
                service.save(
                        m.id(),
                        tenant,
                        "purpose revisado",
                        List.of("outcome final A", "outcome final B"),
                        null);

        MeetingGoal saved = goalRepo.findByMeetingId(m.id(), tenant).orElseThrow();
        assertThat(saved.id()).isEqualTo(after.goal().id());
        assertThat(saved.expectedOutcomes()).hasSize(2);
        assertThat(saved.expectedOutcomes().get(0).text()).isEqualTo("outcome final A");
        assertThat(saved.expectedOutcomes().get(1).text()).isEqualTo("outcome final B");
    }

    @Test
    void saveOnMeetingFromAnotherTenantThrowsNotFound() {
        Meeting m = pendingMeeting(tenant);
        meetingRepo.save(m);

        assertThatThrownBy(
                        () ->
                                service.save(
                                        m.id(), otherTenant, "purpose", List.of("outcome"), null))
                .isInstanceOf(MeetingException.NotFound.class);
        // Makes sure nothing leaked to the other tenant.
        assertThat(goalRepo.findByMeetingId(m.id(), otherTenant)).isEmpty();
        assertThat(goalRepo.findByMeetingId(m.id(), tenant)).isEmpty();
    }

    @Test
    void deleteRemovesGoalAndAssessment() {
        Meeting m = pendingMeeting(tenant);
        meetingRepo.save(m);
        service.save(m.id(), tenant, "purpose", List.of("outcome"), null);
        assessmentRepo.save(
                ProductivityAssessment.newAssessment(
                        tenant,
                        m.id(),
                        70,
                        ProductivityBand.HIGH,
                        List.of(),
                        null,
                        null,
                        "rationale do teste"));

        service.delete(m.id(), tenant);

        assertThat(goalRepo.findByMeetingId(m.id(), tenant)).isEmpty();
        assertThat(assessmentRepo.findByMeetingId(m.id(), tenant)).isEmpty();
    }

    @Test
    void deleteOnDifferentTenantThrowsNotFound() {
        Meeting m = pendingMeeting(tenant);
        meetingRepo.save(m);
        service.save(m.id(), tenant, "purpose", List.of("outcome"), null);

        assertThatThrownBy(() -> service.delete(m.id(), otherTenant))
                .isInstanceOf(MeetingException.NotFound.class);
        assertThat(goalRepo.findByMeetingId(m.id(), tenant)).isPresent();
    }

    private Meeting pendingMeeting(UUID tenantId) {
        return Meeting.newPending(
                tenantId,
                owner,
                "Reuniao X",
                null,
                null,
                "pt-BR",
                TranscriptFormat.TXT,
                List.of(),
                List.of());
    }

    /* ---------- in-memory fakes ---------- */

    static final class InMemoryMeetingRepo implements MeetingRepository {
        private final Map<UUID, Meeting> store = new HashMap<>();

        @Override
        public int hardErase(UUID meetingId, UUID tenantId) {
            return store.remove(meetingId) == null ? 0 : 1;
        }

        @Override
        public int purgeOlderThan(UUID tenantId, java.time.OffsetDateTime cutoff) {
            return 0;
        }

        @Override
        public Meeting save(Meeting meeting) {
            store.put(meeting.id(), meeting);
            return meeting;
        }

        @Override
        public Optional<Meeting> findByIdAndTenant(UUID id, UUID tenantId) {
            Meeting m = store.get(id);
            return (m != null && m.tenantId().equals(tenantId)) ? Optional.of(m) : Optional.empty();
        }

        @Override
        public Optional<Meeting> findByIdAndTenantForUpdate(UUID id, UUID tenantId) {
            // Fake single-thread: there is no concurrency to serialize, the lock is a no-op here.
            return findByIdAndTenant(id, tenantId);
        }

        @Override
        public PagedMeetings listByTenant(UUID tenantId, MeetingFilter filter, int page, int size) {
            List<Meeting> visible = new ArrayList<>();
            for (Meeting m : store.values()) {
                if (m.tenantId().equals(tenantId)) {
                    visible.add(m);
                }
            }
            return new PagedMeetings(visible, visible.size(), page, size);
        }
    }

    static final class InMemoryGoalRepo implements MeetingGoalRepository {
        private final Map<UUID, MeetingGoal> store = new HashMap<>();

        @Override
        public MeetingGoal save(MeetingGoal goal) {
            store.put(key(goal.meetingId(), goal.tenantId()), goal);
            return goal;
        }

        @Override
        public Optional<MeetingGoal> findByMeetingId(UUID meetingId, UUID tenantId) {
            return Optional.ofNullable(store.get(key(meetingId, tenantId)));
        }

        @Override
        public void deleteByMeetingId(UUID meetingId, UUID tenantId) {
            store.remove(key(meetingId, tenantId));
        }

        private static UUID key(UUID meetingId, UUID tenantId) {
            return new UUID(
                    meetingId.getMostSignificantBits() ^ tenantId.getMostSignificantBits(),
                    meetingId.getLeastSignificantBits() ^ tenantId.getLeastSignificantBits());
        }
    }

    static final class InMemoryAssessmentRepo implements ProductivityAssessmentRepository {
        private final Map<UUID, ProductivityAssessment> store = new HashMap<>();

        @Override
        public ProductivityAssessment save(ProductivityAssessment assessment) {
            store.put(key(assessment.meetingId(), assessment.tenantId()), assessment);
            return assessment;
        }

        @Override
        public Optional<ProductivityAssessment> findByMeetingId(UUID meetingId, UUID tenantId) {
            return Optional.ofNullable(store.get(key(meetingId, tenantId)));
        }

        @Override
        public void deleteByMeetingId(UUID meetingId, UUID tenantId) {
            store.remove(key(meetingId, tenantId));
        }

        @Override
        public java.util.List<BandScore> bandsByMeetingIds(
                java.util.Collection<UUID> meetingIds, UUID tenantId) {
            java.util.Set<UUID> ids = new java.util.HashSet<>(meetingIds);
            return store.values().stream()
                    .filter(a -> a.tenantId().equals(tenantId) && ids.contains(a.meetingId()))
                    .map(a -> new BandScore(a.meetingId(), a.band().name(), a.score()))
                    .toList();
        }

        private static UUID key(UUID meetingId, UUID tenantId) {
            return new UUID(
                    meetingId.getMostSignificantBits() ^ tenantId.getMostSignificantBits(),
                    meetingId.getLeastSignificantBits() ^ tenantId.getLeastSignificantBits());
        }
    }
}
