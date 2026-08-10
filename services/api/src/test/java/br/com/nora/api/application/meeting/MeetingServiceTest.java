package br.com.nora.api.application.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.meeting.MeetingService.UploadCommand;
import br.com.nora.api.application.ports.AuditPort;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.MeetingRepository.MeetingFilter;
import br.com.nora.api.application.ports.TranscriptRepository;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.Participant;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.domain.meeting.Transcript;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

class MeetingServiceTest {

    private MeetingService service;
    private InMemoryMeetingRepo meetingRepo;
    private InMemoryTranscriptRepo transcriptRepo;
    private RecordingAudit audit;
    private final UUID tenant = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        meetingRepo = new InMemoryMeetingRepo();
        transcriptRepo = new InMemoryTranscriptRepo();
        audit = new RecordingAudit();
        service =
                new MeetingService(
                        meetingRepo,
                        transcriptRepo,
                        new NullAnalysisProvider(),
                        audit,
                        // No database in this test: the template is only exercised on the
                        // executor's rejection path, covered by the integration tests.
                        new NoOpTransactionManager(),
                        false);
    }

    @Test
    void uploadCreatesMeetingPendingAndPersistsTranscript() {
        UploadCommand cmd =
                new UploadCommand(
                        tenant,
                        owner,
                        "Discovery Acme",
                        null,
                        null,
                        "pt-BR",
                        "TXT",
                        List.of(new Participant("Lucas", "lucas@acme.com", true)),
                        List.of("discovery"),
                        "Lucas: bom dia.\nMarina: bom dia.");

        Meeting saved = service.upload(cmd);

        assertThat(saved.id()).isNotNull();
        assertThat(saved.tenantId()).isEqualTo(tenant);
        assertThat(saved.processingStatus()).isEqualTo(ProcessingStatus.PENDING);
        assertThat(saved.tags()).containsExactly("discovery");
        assertThat(saved.participants()).hasSize(1);

        Transcript t = transcriptRepo.findByMeetingAndTenant(saved.id(), tenant).orElseThrow();
        assertThat(t.rawText()).contains("Lucas: bom dia");
        assertThat(t.charCount()).isEqualTo(t.rawText().length());
    }

    @Test
    void uploadRejectsEmptyTranscript() {
        UploadCommand cmd =
                new UploadCommand(
                        tenant, owner, "x", null, null, null, "TXT", List.of(), List.of(), "  \n");
        assertThatThrownBy(() -> service.upload(cmd))
                .isInstanceOf(MeetingException.EmptyTranscript.class);
    }

    @Test
    void uploadRejectsUnsupportedFormat() {
        UploadCommand cmd =
                new UploadCommand(
                        tenant, owner, "x", null, null, null, "DOCX", List.of(), List.of(), "abc");
        assertThatThrownBy(() -> service.upload(cmd))
                .isInstanceOf(MeetingException.UnsupportedFormat.class);
    }

    @Test
    void uploadRejectsHugeTranscript() {
        String huge = "a".repeat(Transcript.MAX_CHAR_COUNT + 1);
        UploadCommand cmd =
                new UploadCommand(
                        tenant, owner, "x", null, null, null, "TXT", List.of(), List.of(), huge);
        assertThatThrownBy(() -> service.upload(cmd))
                .isInstanceOf(MeetingException.TranscriptTooLarge.class);
    }

    @Test
    void getByIdEnforcesTenantScope() {
        Meeting saved =
                service.upload(
                        new UploadCommand(
                                tenant, owner, "x", null, null, null, "TXT", List.of(), List.of(),
                                "abc"));
        UUID otherTenant = UUID.randomUUID();
        assertThatThrownBy(() -> service.getById(saved.id(), otherTenant))
                .isInstanceOf(MeetingException.NotFound.class);

        Meeting got = service.getById(saved.id(), tenant);
        assertThat(got.id()).isEqualTo(saved.id());
    }

    @Test
    void listReturnsOnlyTenantOwnedMeetings() {
        UUID otherTenant = UUID.randomUUID();
        service.upload(
                new UploadCommand(
                        tenant, owner, "a", null, null, null, "TXT", List.of(), List.of(), "x"));
        service.upload(
                new UploadCommand(
                        tenant, owner, "b", null, null, null, "TXT", List.of(), List.of(), "y"));
        service.upload(
                new UploadCommand(
                        otherTenant,
                        owner,
                        "c",
                        null,
                        null,
                        null,
                        "TXT",
                        List.of(),
                        List.of(),
                        "z"));

        var paged = service.list(tenant, MeetingFilter.empty(), 0, 10);
        assertThat(paged.items()).hasSize(2);
        assertThat(paged.totalItems()).isEqualTo(2);
    }

    @Test
    void listAllForAuthFilterReturnsEveryMeetingAcrossBatches() {
        // > LIST_SCAN_BATCH (200) to force more than one page and prove nothing is truncated
        // (the old AUTH_FILTER_HARD_CAP silently discarded meetings beyond the cap).
        int count = 250;
        for (int i = 0; i < count; i++) {
            service.upload(
                    new UploadCommand(
                            tenant,
                            owner,
                            "m" + i,
                            null,
                            null,
                            null,
                            "TXT",
                            List.of(),
                            List.of(),
                            "linha " + i));
        }

        List<Meeting> all = service.listAllForAuthFilter(tenant, MeetingFilter.empty());

        assertThat(all).hasSize(count);
        assertThat(all.stream().map(Meeting::tenantId).distinct().toList()).containsExactly(tenant);
    }

    @Test
    void reprocessFromCompletedResetsToPending() {
        Meeting m =
                service.upload(
                        new UploadCommand(
                                tenant, owner, "done", null, null, null, "TXT", List.of(),
                                List.of(), "linha"));
        // Valid transition up to COMPLETED (PENDING -> PROCESSING -> COMPLETED).
        meetingRepo.save(
                m.withStatus(ProcessingStatus.PROCESSING).withStatus(ProcessingStatus.COMPLETED));

        Meeting reprocessed = service.reprocess(m.id(), tenant);

        assertThat(reprocessed.processingStatus()).isEqualTo(ProcessingStatus.PENDING);
    }

    @Test
    void reprocessFromFailedResetsToPending() {
        Meeting m =
                service.upload(
                        new UploadCommand(
                                tenant, owner, "failed", null, null, null, "TXT", List.of(),
                                List.of(), "linha"));
        meetingRepo.save(m.withStatus(ProcessingStatus.FAILED));

        Meeting reprocessed = service.reprocess(m.id(), tenant);

        assertThat(reprocessed.processingStatus()).isEqualTo(ProcessingStatus.PENDING);
    }

    @Test
    void reprocessRejectedWhileProcessing() {
        Meeting m =
                service.upload(
                        new UploadCommand(
                                tenant, owner, "busy", null, null, null, "TXT", List.of(),
                                List.of(), "linha"));
        meetingRepo.save(m.withStatus(ProcessingStatus.PROCESSING));

        assertThatThrownBy(() -> service.reprocess(m.id(), tenant))
                .isInstanceOf(MeetingException.CannotReprocess.class);
    }

    @Test
    void reprocessRejectedWhileAlreadyQueued() {
        // PENDING used to pass, and that was why the lock did not prevent the double dispatch: the
        // winner writes PENDING, and the second call -- released by the lock -- read PENDING and
        // passed the guard. A double click scheduled two analyses over the same meeting.
        Meeting m =
                service.upload(
                        new UploadCommand(
                                tenant, owner, "queued", null, null, null, "TXT", List.of(),
                                List.of(), "linha"));
        assertThat(m.processingStatus()).isEqualTo(ProcessingStatus.PENDING);

        assertThatThrownBy(() -> service.reprocess(m.id(), tenant))
                .isInstanceOf(MeetingException.CannotReprocess.class);
    }

    @Test
    void reprocessAuthorizesBeforeItWritesAnything() {
        // A caller without permission must not move the meeting, and must not spend a write on
        // the row before being rejected.
        Meeting m =
                service.upload(
                        new UploadCommand(
                                tenant, owner, "guarded", null, null, null, "TXT", List.of(),
                                List.of(), "linha"));
        meetingRepo.save(
                m.withStatus(ProcessingStatus.PROCESSING).withStatus(ProcessingStatus.COMPLETED));
        meetingRepo.claimCalls = 0;

        assertThatThrownBy(
                        () ->
                                service.reprocess(
                                        m.id(),
                                        tenant,
                                        owner,
                                        loaded -> {
                                            throw new IllegalStateException("denied");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(meetingRepo.claimCalls)
                .as("nothing should have been claimed when authorization fails")
                .isZero();
        assertThat(meetingRepo.findByIdAndTenant(m.id(), tenant).orElseThrow().processingStatus())
                .isEqualTo(ProcessingStatus.COMPLETED);
    }

    @Test
    void theAuditTrailNamesTheCallerAndNotTheMeetingOwner() {
        // The audit row carried meeting.ownerUserId(), which is only ever right when the person
        // clicking "re-analyse" happens to be the uploader. Anyone else acted under the owner's
        // name and left no trace of their own -- so an investigation into who is driving repeated
        // billable re-analyses named the wrong user every time.
        UUID caller = UUID.randomUUID();
        Meeting m =
                service.upload(
                        new UploadCommand(
                                tenant, owner, "audited", null, null, null, "TXT", List.of(),
                                List.of(), "linha"));
        meetingRepo.save(
                m.withStatus(ProcessingStatus.PROCESSING).withStatus(ProcessingStatus.COMPLETED));
        audit.events.clear();

        service.reprocess(m.id(), tenant, caller, loaded -> {});

        RecordingAudit.Event event =
                audit.events.stream()
                        .filter(e -> e.action().equals("meeting.reprocessed"))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no meeting.reprocessed audit row"));
        assertThat(event.actorUserId()).isEqualTo(caller);
        assertThat(event.actorUserId()).isNotEqualTo(owner);
    }

    @Test
    void aWorkerFinishingDuringTheAuthorizationCheckDoesNotTurnIntoA500() {
        // The status is read BEFORE the authorization callback, which in production does two
        // database round trips. A worker committing PROCESSING -> COMPLETED inside that window
        // left the claim to succeed legitimately while the returned object was still built from
        // the stale snapshot: meeting.withStatus(PENDING) ran the domain state machine on
        // PROCESSING, which forbids PROCESSING -> PENDING, and the IllegalStateException rolled
        // back a claim that had already succeeded. A bare 500 on a legitimate re-analyse.
        Meeting m =
                service.upload(
                        new UploadCommand(
                                tenant, owner, "racy", null, null, null, "TXT", List.of(),
                                List.of(), "linha"));
        meetingRepo.save(m.withStatus(ProcessingStatus.PROCESSING));

        Meeting result =
                service.reprocess(
                        m.id(),
                        tenant,
                        owner,
                        // Stands in for the worker finishing while the IAM evaluation is in
                        // flight: the row moves to a terminal state after the snapshot was taken.
                        loaded ->
                                meetingRepo.save(
                                        meetingRepo
                                                .findByIdAndTenant(m.id(), tenant)
                                                .orElseThrow()
                                                .withStatus(ProcessingStatus.COMPLETED)));

        assertThat(result.processingStatus()).isEqualTo(ProcessingStatus.PENDING);
        assertThat(meetingRepo.findByIdAndTenant(m.id(), tenant).orElseThrow().processingStatus())
                .isEqualTo(ProcessingStatus.PENDING);

        // The audit row must not assert a transition the database refuses. The snapshot said
        // PROCESSING; the claim only fires from COMPLETED or FAILED, so which one it passed
        // through is unknowable here -- and recording "previousStatus": "PROCESSING" filed a
        // permanent claim that PROCESSING -> PENDING happened, which it cannot have.
        RecordingAudit.Event event =
                audit.events.stream()
                        .filter(e -> e.action().equals("meeting.reprocessed"))
                        .findFirst()
                        .orElseThrow();
        assertThat(event.payload()).containsEntry("previousStatus", "UNKNOWN");
        assertThat(event.payload()).containsEntry("observedBeforeClaim", "PROCESSING");
    }

    @Test
    void theAuditRecordsTheRealPreviousStatusWhenNothingMovedUnderIt() {
        // Counter-proof: in the ordinary case the snapshot IS the previous status, and saying
        // "UNKNOWN" everywhere would make the field useless.
        Meeting m =
                service.upload(
                        new UploadCommand(
                                tenant, owner, "calm", null, null, null, "TXT", List.of(),
                                List.of(), "linha"));
        meetingRepo.save(
                m.withStatus(ProcessingStatus.PROCESSING).withStatus(ProcessingStatus.COMPLETED));
        audit.events.clear();

        service.reprocess(m.id(), tenant, owner, loaded -> {});

        RecordingAudit.Event event =
                audit.events.stream()
                        .filter(e -> e.action().equals("meeting.reprocessed"))
                        .findFirst()
                        .orElseThrow();
        assertThat(event.payload()).containsEntry("previousStatus", "COMPLETED");
        assertThat(event.payload()).doesNotContainKey("observedBeforeClaim");
    }

    @Test
    void onlyOneOfTwoConcurrentReprocessesClaimsTheMeeting() {
        // The real invariant: the transition is what gates the dispatch. The previous shape read
        // the status, checked it and then wrote -- and a row lock did not close that window,
        // because the authorization read had already put the entity in the persistence context
        // and Hibernate served the locked SELECT from it without refreshing.
        Meeting m =
                service.upload(
                        new UploadCommand(
                                tenant, owner, "race", null, null, null, "TXT", List.of(),
                                List.of(), "linha"));
        meetingRepo.save(
                m.withStatus(ProcessingStatus.PROCESSING).withStatus(ProcessingStatus.COMPLETED));

        service.reprocess(m.id(), tenant); // first click wins

        assertThatThrownBy(() -> service.reprocess(m.id(), tenant)) // second finds it queued
                .isInstanceOf(MeetingException.CannotReprocess.class);
    }

    /* ---------- in-memory fakes ---------- */

    /** Keeps every audit call so a test can assert WHO was recorded, not just that it happened. */
    static final class RecordingAudit implements AuditPort {
        record Event(
                UUID tenantId,
                UUID actorUserId,
                String action,
                String entityType,
                UUID entityId,
                Map<String, Object> payload) {}

        final List<Event> events = new ArrayList<>();

        @Override
        public void record(
                UUID tenantId,
                UUID actorUserId,
                String action,
                String entityType,
                UUID entityId,
                Map<String, Object> payload) {
            events.add(new Event(tenantId, actorUserId, action, entityType, entityId, payload));
        }
    }

    static final class InMemoryMeetingRepo implements MeetingRepository {
        private final Map<UUID, Meeting> store = new HashMap<>();

        /** How many times the atomic claim was requested. See the authorization-order test. */
        int claimCalls;

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
        public int claimForReanalysis(UUID id, UUID tenantId) {
            claimCalls++;
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
        public PagedMeetings listByTenant(UUID tenantId, MeetingFilter filter, int page, int size) {
            List<Meeting> all = new ArrayList<>();
            for (Meeting m : store.values()) {
                if (m.tenantId().equals(tenantId)) {
                    all.add(m);
                }
            }
            all.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
            int from = Math.min(page * size, all.size());
            int to = Math.min(from + size, all.size());
            return new PagedMeetings(all.subList(from, to), all.size(), page, size);
        }
    }

    static final class InMemoryTranscriptRepo implements TranscriptRepository {
        private final Map<UUID, Transcript> store = new HashMap<>();

        @Override
        public Transcript save(Transcript transcript) {
            store.put(transcript.meetingId(), transcript);
            return transcript;
        }

        @Override
        public Optional<Transcript> findByMeetingAndTenant(UUID meetingId, UUID tenantId) {
            Transcript t = store.get(meetingId);
            return (t != null && t.tenantId().equals(tenantId)) ? Optional.of(t) : Optional.empty();
        }
    }

    /** Provider that never returns an AnalysisService — async dispatch is a no-op in tests. */
    /**
     * Runs the callback with no transaction at all. This test runs with in-memory repositories;
     * REQUIRES_NEW only matters when there is a real JPA transaction manager, and that part is
     * covered by the integration tests.
     */
    static final class NoOpTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {}

        @Override
        public void rollback(TransactionStatus status) {}
    }

    static final class NullAnalysisProvider implements ObjectProvider<AnalysisService> {
        @Override
        public AnalysisService getObject(Object... args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AnalysisService getObject() {
            throw new UnsupportedOperationException();
        }

        @Override
        public AnalysisService getIfAvailable() {
            return null;
        }

        @Override
        public AnalysisService getIfUnique() {
            return null;
        }
    }
}
