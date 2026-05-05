package br.com.nora.api.application.ports;

import br.com.nora.api.domain.meeting.Meeting;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Porta de persistencia de reunioes. Toda consulta exige tenantId para enforcement. */
public interface MeetingRepository {

    Meeting save(Meeting meeting);

    Optional<Meeting> findByIdAndTenant(UUID id, UUID tenantId);

    /** Lista paginada por tenant ordenada por created_at desc. Page e size sao 0-based. */
    PagedMeetings listByTenant(UUID tenantId, int page, int size);

    record PagedMeetings(List<Meeting> items, long totalItems, int page, int size) {
        public int totalPages() {
            if (size <= 0) {
                return 0;
            }
            return (int) Math.ceil((double) totalItems / (double) size);
        }
    }
}
