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

class MeetingServiceTest {

    private MeetingService service;
    private InMemoryMeetingRepo meetingRepo;
    private InMemoryTranscriptRepo transcriptRepo;
    private final UUID tenant = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        meetingRepo = new InMemoryMeetingRepo();
        transcriptRepo = new InMemoryTranscriptRepo();
        AuditPort noOpAudit = (a, b, c, d, e, f) -> {};
        service =
                new MeetingService(
                        meetingRepo, transcriptRepo, new NullAnalysisProvider(), noOpAudit, false);
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
        // > LIST_SCAN_BATCH (200) para forcar mais de uma pagina e provar que nada eh truncado
        // (o antigo AUTH_FILTER_HARD_CAP descartava silenciosamente meetings alem do teto).
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

    /** Provider que nunca devolve um AnalysisService — dispatch async vira no-op nos testes. */
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
