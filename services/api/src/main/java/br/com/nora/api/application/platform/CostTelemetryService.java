package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.CostReport;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Telemetria de custo (frente (a), ADR 0024). Lê usage_events do banco de plataforma. */
@Service
public class CostTelemetryService {

    private static final Set<String> GROUPS = Set.of("tenant", "model", "service");

    private final ObjectProvider<UsageEventRepository> eventsProvider;
    private final PlatformAvailability availability;

    public CostTelemetryService(
            ObjectProvider<UsageEventRepository> eventsProvider, PlatformAvailability availability) {
        this.eventsProvider = eventsProvider;
        this.availability = availability;
    }

    public CostReport cost(OffsetDateTime from, OffsetDateTime to, String groupBy) {
        if (!availability.isUsable()) {
            throw new PlatformUnavailableException("control plane indisponível (banco de plataforma)");
        }
        String g = groupBy == null || groupBy.isBlank() ? "model" : groupBy.trim().toLowerCase();
        if (!GROUPS.contains(g)) {
            throw new PlatformValidationException(
                    "groupBy inválido: " + groupBy + " (use tenant|model|service)", false);
        }
        try {
            return eventsProvider.getObject().aggregate(from, to, g);
        } catch (DataAccessException ex) {
            // Banco de plataforma caiu em runtime → 503 (não 500), per-call.
            throw new PlatformUnavailableException("banco de plataforma indisponível em runtime");
        }
    }
}
