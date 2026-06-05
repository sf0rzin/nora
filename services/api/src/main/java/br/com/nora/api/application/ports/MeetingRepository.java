package br.com.nora.api.application.ports;

import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistencia de reunioes. Toda consulta exige tenantId para enforcement. */
public interface MeetingRepository {

    Meeting save(Meeting meeting);

    Optional<Meeting> findByIdAndTenant(UUID id, UUID tenantId);

    /** Lista paginada por tenant ordenada por created_at desc. Page e size sao 0-based. */
    PagedMeetings listByTenant(UUID tenantId, MeetingFilter filter, int page, int size);

    /**
     * Hard-delete FISICO de um meeting (LGPD: direito ao esquecimento, ADR 0029). Ignora o
     * soft-delete; o FK CASCADE purga transcript (raw_text = PII), participants, tags e analises.
     * Retorna linhas afetadas (0 = nao existia no tenant).
     */
    int hardErase(UUID meetingId, UUID tenantId);

    /**
     * Hard-delete FISICO de meetings do tenant criados antes de {@code cutoff} (retencao, ADR
     * 0029). Retorna quantos foram purgados.
     */
    int purgeOlderThan(UUID tenantId, OffsetDateTime cutoff);

    /**
     * Filtros opcionais para a listagem. Qualquer campo nulo significa "sem restricao". O search
     * casa por substring case-insensitive sobre o titulo.
     */
    record MeetingFilter(
            String search, ProcessingStatus status, OffsetDateTime from, OffsetDateTime to) {
        public static MeetingFilter empty() {
            return new MeetingFilter(null, null, null, null);
        }
    }

    record PagedMeetings(List<Meeting> items, long totalItems, int page, int size) {
        public int totalPages() {
            if (size <= 0) {
                return 0;
            }
            return (int) Math.ceil((double) totalItems / (double) size);
        }
    }
}
