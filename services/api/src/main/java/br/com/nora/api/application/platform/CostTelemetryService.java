package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.CostReport;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Cost telemetry (track (a), ADR 0024). Reads usage_events from the platform database. */
@Service
public class CostTelemetryService {

    private static final Set<String> GROUPS = Set.of("tenant", "model", "service");

    private final ObjectProvider<UsageEventRepository> eventsProvider;
    private final PlatformAvailability availability;

    public CostTelemetryService(
            ObjectProvider<UsageEventRepository> eventsProvider,
            PlatformAvailability availability) {
        this.eventsProvider = eventsProvider;
        this.availability = availability;
    }

    public CostReport cost(OffsetDateTime from, OffsetDateTime to, String groupBy) {
        if (!availability.isUsable()) {
            throw new PlatformUnavailableException("control plane unavailable (platform database)");
        }
        String g = groupBy == null || groupBy.isBlank() ? "model" : groupBy.trim().toLowerCase();
        if (!GROUPS.contains(g)) {
            throw new PlatformValidationException(
                    "invalid groupBy: " + groupBy + " (use tenant|model|service)", false);
        }
        try {
            return eventsProvider.getObject().aggregate(from, to, g);
        } catch (DataAccessException ex) {
            // Platform database went down at runtime → 503 (not 500), per-call.
            throw new PlatformUnavailableException("platform database unavailable at runtime");
        }
    }
}
