package br.com.nora.api.application.meeting;

import br.com.nora.api.application.analysis.AnalysisService;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.MeetingRepository.MeetingFilter;
import br.com.nora.api.application.ports.MeetingRepository.PagedMeetings;
import br.com.nora.api.application.ports.TranscriptRepository;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.Participant;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.domain.meeting.Transcript;
import br.com.nora.api.domain.meeting.TranscriptFormat;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Servico de aplicacao para reunioes (US07).
 *
 * <p>Regras chave:
 *
 * <ul>
 *   <li>Toda escrita/leitura recebe tenantId explicito vindo do JWT do chamador.
 *   <li>Upload cria meeting + transcript em uma unica transacao.
 *   <li>Apos commit do upload, dispara analise async via {@link AnalysisService#runAsync}.
 * </ul>
 */
@Service
public class MeetingService {

    private final MeetingRepository meetings;
    private final TranscriptRepository transcripts;
    private final ObjectProvider<AnalysisService> analysisServiceProvider;
    private final boolean autoDispatchAnalysis;

    public MeetingService(
            MeetingRepository meetings,
            TranscriptRepository transcripts,
            ObjectProvider<AnalysisService> analysisServiceProvider,
            @Value("${nora.analysis.auto-dispatch:true}") boolean autoDispatchAnalysis) {
        this.meetings = meetings;
        this.transcripts = transcripts;
        this.analysisServiceProvider = analysisServiceProvider;
        this.autoDispatchAnalysis = autoDispatchAnalysis;
    }

    @Transactional
    public Meeting upload(UploadCommand cmd) {
        if (cmd.rawTranscript() == null || cmd.rawTranscript().isBlank()) {
            throw new MeetingException.EmptyTranscript();
        }
        if (cmd.rawTranscript().length() > Transcript.MAX_CHAR_COUNT) {
            throw new MeetingException.TranscriptTooLarge(Transcript.MAX_CHAR_COUNT);
        }
        TranscriptFormat format;
        try {
            format = TranscriptFormat.fromString(cmd.format());
        } catch (IllegalArgumentException ex) {
            throw new MeetingException.UnsupportedFormat(cmd.format());
        }

        Meeting meeting =
                Meeting.newPending(
                        cmd.tenantId(),
                        cmd.ownerUserId(),
                        cmd.title(),
                        cmd.startedAt(),
                        cmd.endedAt(),
                        cmd.language(),
                        format,
                        cmd.participants(),
                        cmd.tags());
        Meeting saved = meetings.save(meeting);
        Transcript transcript =
                Transcript.create(saved.id(), saved.tenantId(), format, cmd.rawTranscript());
        transcripts.save(transcript);

        scheduleAnalysisAfterCommit(saved.id(), saved.tenantId());
        return saved;
    }

    private void scheduleAnalysisAfterCommit(UUID meetingId, UUID tenantId) {
        if (!autoDispatchAnalysis) {
            return;
        }
        AnalysisService svc = analysisServiceProvider.getIfAvailable();
        if (svc == null) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            svc.runAsync(meetingId, tenantId);
                        }
                    });
        } else {
            svc.runAsync(meetingId, tenantId);
        }
    }

    @Transactional(readOnly = true)
    public PagedMeetings list(UUID tenantId, MeetingFilter filter, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        return meetings.listByTenant(
                tenantId, filter == null ? MeetingFilter.empty() : filter, safePage, safeSize);
    }

    @Transactional(readOnly = true)
    public Meeting getById(UUID meetingId, UUID tenantId) {
        return meetings.findByIdAndTenant(meetingId, tenantId)
                .orElseThrow(MeetingException.NotFound::new);
    }

    @Transactional
    public Meeting reprocess(UUID meetingId, UUID tenantId) {
        Meeting meeting =
                meetings.findByIdAndTenant(meetingId, tenantId)
                        .orElseThrow(MeetingException.NotFound::new);
        if (meeting.processingStatus() != ProcessingStatus.FAILED) {
            throw new MeetingException.CannotReprocess(
                    "Only meetings in FAILED status can be reprocessed. Current status: "
                            + meeting.processingStatus());
        }
        Meeting updated = meeting.withStatus(ProcessingStatus.PENDING);
        meetings.save(updated);
        scheduleAnalysisAfterCommit(meetingId, tenantId);
        return updated;
    }

    @Transactional(readOnly = true)
    public Transcript getTranscript(UUID meetingId, UUID tenantId) {
        // Garante escopo: a meeting precisa existir no tenant antes de devolver o texto.
        meetings.findByIdAndTenant(meetingId, tenantId).orElseThrow(MeetingException.NotFound::new);
        return transcripts
                .findByMeetingAndTenant(meetingId, tenantId)
                .orElseThrow(MeetingException.NotFound::new);
    }

    /** Comando imutavel de upload. */
    public record UploadCommand(
            UUID tenantId,
            UUID ownerUserId,
            String title,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            String language,
            String format,
            List<Participant> participants,
            List<String> tags,
            String rawTranscript) {}
}
