package br.com.nora.api.application.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.TenantRlsContext;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.domain.meeting.TranscriptFormat;
import br.com.nora.api.domain.tenant.Tenant;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StuckAnalysisSweeperTest {

    private static final long WORKER_TIMEOUT_MILLIS = 120_000L;
    private static final long WINDOW_MINUTES = 30L;

    private InMemoryMeetingRepo meetings;
    private StubTenantRepo tenants;
    private RecordingRlsContext rls;
    private final UUID tenant = UUID.randomUUID();
    private final UUID otherTenant = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        meetings = new InMemoryMeetingRepo();
        tenants = new StubTenantRepo();
        rls = new RecordingRlsContext();
    }

    private StuckAnalysisSweeper sweeper() {
        return new StuckAnalysisSweeper(
                meetings, tenants, rls, WINDOW_MINUTES, WORKER_TIMEOUT_MILLIS);
    }

    @Test
    void aMeetingAbandonedInProcessingIsReleasedToFailed() {
        // The JVM died during the worker roundtrip, so AnalysisService's catch never ran and
        // nothing else ever writes this row.
        UUID id = meetings.seed(tenant, ProcessingStatus.PROCESSING, minutesAgo(90));
        tenants.active(tenant);

        sweeper().sweep();

        assertThat(meetings.statusOf(id)).isEqualTo(ProcessingStatus.FAILED);
    }

    @Test
    void aProcessingMeetingInsideTheWindowIsLeftAlone() {
        // The whole risk of this job: reaping an analysis that is merely slow reports a failure
        // for work that was about to succeed.
        UUID id = meetings.seed(tenant, ProcessingStatus.PROCESSING, minutesAgo(3));
        tenants.active(tenant);

        sweeper().sweep();

        assertThat(meetings.statusOf(id)).isEqualTo(ProcessingStatus.PROCESSING);
    }

    @Test
    void noStatusOtherThanProcessingIsTouched() {
        // PENDING has its own staleness rule inside the re-analysis claim; COMPLETED and FAILED are
        // terminal. Age alone must not move any of them.
        UUID pending = meetings.seed(tenant, ProcessingStatus.PENDING, minutesAgo(600));
        UUID completed = meetings.seed(tenant, ProcessingStatus.COMPLETED, minutesAgo(600));
        UUID failed = meetings.seed(tenant, ProcessingStatus.FAILED, minutesAgo(600));
        tenants.active(tenant);

        sweeper().sweep();

        assertThat(meetings.statusOf(pending)).isEqualTo(ProcessingStatus.PENDING);
        assertThat(meetings.statusOf(completed)).isEqualTo(ProcessingStatus.COMPLETED);
        assertThat(meetings.statusOf(failed)).isEqualTo(ProcessingStatus.FAILED);
    }

    @Test
    void aReleasedMeetingCanBeReanalysedAgain() {
        // The point of the whole job: FAILED is claimable, PROCESSING is not, so the re-analyse
        // button comes back.
        UUID id = meetings.seed(tenant, ProcessingStatus.PROCESSING, minutesAgo(90));
        tenants.active(tenant);
        assertThat(meetings.claimForReanalysis(id, tenant))
                .as("a PROCESSING meeting must not be claimable before the sweep")
                .isZero();

        sweeper().sweep();

        assertThat(meetings.claimForReanalysis(id, tenant)).isEqualTo(1);
    }

    @Test
    void anotherTenantsAbandonedMeetingIsNotSweptUnderThisTenant() {
        UUID mine = meetings.seed(tenant, ProcessingStatus.PROCESSING, minutesAgo(90));
        UUID theirs = meetings.seed(otherTenant, ProcessingStatus.PROCESSING, minutesAgo(90));
        tenants.active(tenant);

        sweeper().sweep();

        assertThat(meetings.statusOf(mine)).isEqualTo(ProcessingStatus.FAILED);
        assertThat(meetings.statusOf(theirs)).isEqualTo(ProcessingStatus.PROCESSING);
    }

    @Test
    void theTenantIsBoundBeforeEachSweepAndClearedAfterIt() {
        // Under RLS enforce the scheduler thread carries no JWT: without the GUC the UPDATE matches
        // nothing and the job reports zero forever. Leaving it set leaks the tenant into whatever
        // the pool thread runs next.
        tenants.active(tenant, otherTenant);

        sweeper().sweep();

        assertThat(rls.calls)
                .containsExactly("set:" + tenant, "clear", "set:" + otherTenant, "clear");
    }

    @Test
    void aTenantThatBlowsUpDoesNotStopTheRest() {
        UUID survivor = meetings.seed(otherTenant, ProcessingStatus.PROCESSING, minutesAgo(90));
        meetings.failFor(tenant);
        tenants.active(tenant, otherTenant);

        sweeper().sweep();

        assertThat(meetings.statusOf(survivor)).isEqualTo(ProcessingStatus.FAILED);
        assertThat(rls.calls).endsWith("clear");
    }

    @Test
    void aWindowBelowTheWorkerTimeoutIsRefusedAndTheFloorIsUsedInstead() {
        // A one-minute window with a two-minute worker timeout would mark as failed every analysis
        // still legitimately in flight. Configuration must not be able to express that.
        StuckAnalysisSweeper tight =
                new StuckAnalysisSweeper(meetings, tenants, rls, 1L, WORKER_TIMEOUT_MILLIS);

        Duration floor =
                Duration.ofMillis(WORKER_TIMEOUT_MILLIS).plus(StuckAnalysisSweeper.MINIMUM_MARGIN);
        assertThat(tight.window()).isEqualTo(floor);
    }

    @Test
    void aWindowAboveTheFloorIsHonoured() {
        assertThat(sweeper().window()).isEqualTo(Duration.ofMinutes(WINDOW_MINUTES));
    }

    @Test
    void theFloorFollowsTheWorkerTimeoutUpwards() {
        // Raising the worker timeout past the configured window must raise the floor with it,
        // otherwise the guard silently stops guarding.
        StuckAnalysisSweeper slowWorker =
                new StuckAnalysisSweeper(
                        meetings, tenants, rls, WINDOW_MINUTES, Duration.ofHours(1).toMillis());

        assertThat(slowWorker.window())
                .isEqualTo(Duration.ofHours(1).plus(StuckAnalysisSweeper.MINIMUM_MARGIN));
    }

    private static OffsetDateTime minutesAgo(int minutes) {
        return OffsetDateTime.now().minusMinutes(minutes);
    }

    /* ---------- in-memory fakes ---------- */

    /** Mirrors the conditional UPDATE the adapter issues: tenant + PROCESSING + old updated_at. */
    static final class InMemoryMeetingRepo implements MeetingRepository {
        private final Map<UUID, Meeting> store = new LinkedHashMap<>();
        private final List<UUID> exploding = new ArrayList<>();

        UUID seed(UUID tenantId, ProcessingStatus status, OffsetDateTime updatedAt) {
            UUID id = UUID.randomUUID();
            store.put(
                    id,
                    new Meeting(
                            id,
                            tenantId,
                            UUID.randomUUID(),
                            "Meeting " + id.toString().substring(0, 4),
                            null,
                            null,
                            "pt-BR",
                            TranscriptFormat.TXT,
                            status,
                            null,
                            List.of(),
                            List.of(),
                            updatedAt.minusMinutes(1),
                            updatedAt));
            return id;
        }

        void failFor(UUID tenantId) {
            exploding.add(tenantId);
        }

        ProcessingStatus statusOf(UUID id) {
            return store.get(id).processingStatus();
        }

        @Override
        public int failStuckProcessing(UUID tenantId, OffsetDateTime staleBefore) {
            if (exploding.contains(tenantId)) {
                throw new IllegalStateException("connection reset");
            }
            int affected = 0;
            for (UUID id : List.copyOf(store.keySet())) {
                Meeting m = store.get(id);
                if (!m.tenantId().equals(tenantId)
                        || m.processingStatus() != ProcessingStatus.PROCESSING
                        || !m.updatedAt().isBefore(staleBefore)) {
                    continue;
                }
                store.put(id, m.withStatus(ProcessingStatus.FAILED));
                affected++;
            }
            return affected;
        }

        @Override
        public int claimForReanalysis(UUID id, UUID tenantId) {
            Meeting m = store.get(id);
            if (m == null || !m.tenantId().equals(tenantId)) {
                return 0;
            }
            if (m.processingStatus() != ProcessingStatus.COMPLETED
                    && m.processingStatus() != ProcessingStatus.FAILED) {
                return 0;
            }
            store.put(id, m.withStatus(ProcessingStatus.PENDING));
            return 1;
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
        public PagedMeetings listByTenant(UUID tenantId, MeetingFilter filter, int page, int size) {
            List<Meeting> visible = new ArrayList<>();
            for (Meeting m : store.values()) {
                if (m.tenantId().equals(tenantId)) {
                    visible.add(m);
                }
            }
            return new PagedMeetings(visible, visible.size(), page, size);
        }

        @Override
        public int hardErase(UUID meetingId, UUID tenantId) {
            return store.remove(meetingId) == null ? 0 : 1;
        }

        @Override
        public int purgeOlderThan(UUID tenantId, OffsetDateTime cutoff) {
            return 0;
        }
    }

    static final class StubTenantRepo implements TenantRepository {
        private final List<UUID> ids = new ArrayList<>();

        void active(UUID... tenantIds) {
            ids.clear();
            ids.addAll(List.of(tenantIds));
        }

        @Override
        public List<UUID> allActiveTenantIds() {
            return List.copyOf(ids);
        }

        @Override
        public Optional<Tenant> findById(UUID id) {
            return Optional.empty();
        }

        @Override
        public boolean existsBySlug(String slug) {
            return false;
        }

        @Override
        public Tenant save(Tenant tenant) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void hardDelete(UUID tenantId) {
            throw new UnsupportedOperationException();
        }
    }

    /** Records the order of set/clear so the test can prove the pairing, not just the calls. */
    static final class RecordingRlsContext implements TenantRlsContext {
        final List<String> calls = new ArrayList<>();

        @Override
        public void set(UUID tenantId) {
            calls.add("set:" + tenantId);
        }

        @Override
        public void clear() {
            calls.add("clear");
        }
    }
}
