package br.com.nora.api.infrastructure.persistence.meeting;

import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.MeetingRepository.MeetingFilter;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.Participant;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.domain.meeting.TranscriptFormat;
import br.com.nora.api.infrastructure.persistence.meeting.MeetingTagJpaEntity.MeetingTagId;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adapter entre {@link MeetingJpaRepository} e a porta de dominio.
 *
 * <p>Os metodos sao {@code @Transactional} porque {@link #toDomain} acessa as colecoes {@code
 * participants} e {@code tags} (ambas LAZY). Sem a sessao aberta aqui, callers que chamam o adapter
 * fora de um {@code @Transactional} caller (ex.: {@code AnalysisService.run}) sofreriam {@code
 * LazyInitializationException}.
 */
@Repository
public class MeetingRepositoryAdapter implements MeetingRepository {

    private final MeetingJpaRepository jpa;

    public MeetingRepositoryAdapter(MeetingJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public Meeting save(Meeting meeting) {
        MeetingJpaEntity entity = toEntity(meeting);
        MeetingJpaEntity saved = jpa.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Meeting> findByIdAndTenant(UUID id, UUID tenantId) {
        return jpa.findByIdAndTenantId(id, tenantId).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedMeetings listByTenant(UUID tenantId, MeetingFilter filter, int page, int size) {
        MeetingFilter f = filter == null ? MeetingFilter.empty() : filter;
        String search = (f.search() == null || f.search().isBlank()) ? null : f.search().trim();
        String status = f.status() == null ? null : f.status().name();
        Page<MeetingJpaEntity> result =
                jpa.search(tenantId, search, status, f.from(), f.to(), PageRequest.of(page, size));
        List<Meeting> items = result.getContent().stream().map(this::toDomain).toList();
        return new PagedMeetings(items, result.getTotalElements(), page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTenant(UUID tenantId) {
        return jpa.countByTenantId(tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTenantAndStatus(UUID tenantId, ProcessingStatus status) {
        return jpa.countByTenantIdAndProcessingStatus(tenantId, status.name());
    }

    @Override
    @Transactional(readOnly = true)
    public long countByTenantCreatedSince(UUID tenantId, OffsetDateTime from) {
        return jpa.countByTenantIdAndCreatedAtGreaterThanEqual(tenantId, from);
    }

    private MeetingJpaEntity toEntity(Meeting m) {
        MeetingJpaEntity e = new MeetingJpaEntity();
        e.setId(m.id());
        e.setTenantId(m.tenantId());
        e.setOwnerUserId(m.ownerUserId());
        e.setProjectId(m.projectId());
        e.setTitle(m.title());
        e.setStartedAt(m.startedAt());
        e.setEndedAt(m.endedAt());
        e.setLanguage(m.language());
        e.setTranscriptFormat(m.transcriptFormat().name());
        e.setProcessingStatus(m.processingStatus().name());
        e.setSummarySnippet(m.summarySnippet());
        e.setAttributes(m.attributes() == null ? new HashMap<>() : new HashMap<>(m.attributes()));
        e.setCreatedAt(m.createdAt());
        e.setUpdatedAt(m.updatedAt());

        for (Participant p : m.participants()) {
            MeetingParticipantJpaEntity pe = new MeetingParticipantJpaEntity();
            pe.setId(UUID.randomUUID());
            pe.setMeeting(e);
            pe.setTenantId(m.tenantId());
            pe.setDisplayName(p.displayName());
            pe.setEmail(p.email());
            pe.setInternal(p.isInternal());
            pe.setCreatedAt(OffsetDateTime.now());
            e.getParticipants().add(pe);
        }
        for (String tag : m.tags()) {
            MeetingTagJpaEntity te = new MeetingTagJpaEntity();
            te.setId(new MeetingTagId(m.id(), tag));
            te.setTenantId(m.tenantId());
            e.getTags().add(te);
        }
        return e;
    }

    private Meeting toDomain(MeetingJpaEntity e) {
        List<Participant> participants =
                e.getParticipants().stream()
                        .map(p -> new Participant(p.getDisplayName(), p.getEmail(), p.isInternal()))
                        .toList();
        List<String> tags = e.getTags().stream().map(t -> t.getId().getTag()).toList();
        return new Meeting(
                e.getId(),
                e.getTenantId(),
                e.getOwnerUserId(),
                e.getProjectId(),
                e.getTitle(),
                e.getStartedAt(),
                e.getEndedAt(),
                e.getLanguage(),
                TranscriptFormat.valueOf(e.getTranscriptFormat()),
                ProcessingStatus.valueOf(e.getProcessingStatus()),
                e.getSummarySnippet(),
                participants,
                tags,
                e.getAttributes(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
