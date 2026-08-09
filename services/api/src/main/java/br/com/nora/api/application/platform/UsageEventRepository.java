package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.CostReport;
import br.com.nora.api.domain.platform.UsageEvent;
import java.time.OffsetDateTime;

/** Persistence/aggregation port for AI usage events (usage_events table, ADR 0024). */
public interface UsageEventRepository {

    void insert(UsageEvent event);

    /** Aggregates cost by {@code groupBy} ∈ tenant|model|service in the window [from, to). */
    CostReport aggregate(OffsetDateTime from, OffsetDateTime to, String groupBy);
}
