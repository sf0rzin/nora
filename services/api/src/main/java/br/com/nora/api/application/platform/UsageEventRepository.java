package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.CostReport;
import br.com.nora.api.domain.platform.UsageEvent;
import java.time.OffsetDateTime;

/** Porta de persistência/agregação de eventos de uso de IA (tabela usage_events, ADR 0024). */
public interface UsageEventRepository {

    void insert(UsageEvent event);

    /** Agrega custo por {@code groupBy} ∈ tenant|model|service na janela [from, to). */
    CostReport aggregate(OffsetDateTime from, OffsetDateTime to, String groupBy);
}
