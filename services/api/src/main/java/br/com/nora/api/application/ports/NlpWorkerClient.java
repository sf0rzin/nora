package br.com.nora.api.application.ports;

import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;

/**
 * Cliente do NLP Worker (FastAPI). Implementacao infrastructure faz a chamada HTTP /analyze e
 * retorna o agregado ja construido (sem id/createdAt persistido).
 */
public interface NlpWorkerClient {

    /**
     * Submete o texto ao worker e retorna a analise validada.
     *
     * @param meetingId id da reuniao (correlation id)
     * @param tenantId id do tenant dono
     * @param language ISO 639-1 (ex: "pt-BR"), pode ser null
     * @param transcript texto bruto da transcricao (apos normalizacao do upload)
     * @param tenantContext contexto comercial opcional usado no prompt
     */
    MeetingAnalysis analyze(
            UUID meetingId,
            UUID tenantId,
            String language,
            String transcript,
            Optional<TenantContext> tenantContext);
}
