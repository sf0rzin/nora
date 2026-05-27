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

    /** Total de reunioes do tenant (US33 metrics). */
    long countByTenant(UUID tenantId);

    /** Total de reunioes do tenant em um status de processamento (US33 metrics). */
    long countByTenantAndStatus(UUID tenantId, ProcessingStatus status);

    /** Total de reunioes do tenant criadas em ou apos {@code from} (US33 "este mes"). */
    long countByTenantCreatedSince(UUID tenantId, OffsetDateTime from);

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
