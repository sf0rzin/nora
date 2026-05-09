package br.com.nora.api.application.ports;

import br.com.nora.api.domain.analysis.MeetingAnalysis;
import java.util.Optional;
import java.util.UUID;

/** Persistencia da analise gerada pelo NLP worker. Sempre escopada por tenant. */
public interface MeetingAnalysisRepository {

    MeetingAnalysis save(MeetingAnalysis analysis);

    Optional<MeetingAnalysis> findByMeetingId(UUID meetingId, UUID tenantId);

    /** Apaga analise existente do meeting (idempotente). Util para reprocessamento. */
    void deleteByMeetingId(UUID meetingId, UUID tenantId);
}
