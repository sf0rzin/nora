package br.com.nora.api.application.tenant;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.TaskRepository;
import br.com.nora.api.domain.analysis.ActionItemStatus;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Visao rapida de atividade do tenant (US33). Apenas contadores agregados — sem migration. Toda
 * consulta recebe o tenantId do principal autenticado, garantindo isolamento (ADR 0002).
 */
@Service
public class MetricsService {

    private final MeetingRepository meetings;
    private final TaskRepository tasks;
    private final Clock clock;

    public MetricsService(MeetingRepository meetings, TaskRepository tasks, Clock clock) {
        this.meetings = meetings;
        this.tasks = tasks;
        this.clock = clock;
    }

    /** Contadores imutaveis devolvidos a camada de API. */
    public record TenantMetrics(
            long totalMeetings,
            long meetingsThisMonth,
            long completed,
            long processing,
            long pending,
            long failed,
            long totalActionItems,
            long openActionItems) {}

    @Transactional(readOnly = true)
    public TenantMetrics forTenant(UUID tenantId) {
        OffsetDateTime monthStart = firstDayOfCurrentMonth();
        return new TenantMetrics(
                meetings.countByTenant(tenantId),
                meetings.countByTenantCreatedSince(tenantId, monthStart),
                meetings.countByTenantAndStatus(tenantId, ProcessingStatus.COMPLETED),
                meetings.countByTenantAndStatus(tenantId, ProcessingStatus.PROCESSING),
                meetings.countByTenantAndStatus(tenantId, ProcessingStatus.PENDING),
                meetings.countByTenantAndStatus(tenantId, ProcessingStatus.FAILED),
                tasks.countByTenant(tenantId),
                tasks.countByTenantAndStatus(tenantId, ActionItemStatus.OPEN));
    }

    private OffsetDateTime firstDayOfCurrentMonth() {
        return clock.now()
                .atOffset(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .toLocalDate()
                .atStartOfDay()
                .atOffset(ZoneOffset.UTC);
    }
}
