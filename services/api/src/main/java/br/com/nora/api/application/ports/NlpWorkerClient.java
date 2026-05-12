package br.com.nora.api.application.ports;

import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import br.com.nora.api.infrastructure.nlp.WorkerDtos.LiveAnalyzeResponse;
import java.util.Optional;
import java.util.UUID;

/**
 * Cliente do NLP Worker (FastAPI). Implementacao infrastructure faz a chamada HTTP /analyze e
 * retorna o agregado ja construido (sem id/createdAt persistido) junto com o productivity opt-in
 * (ADR 0005), quando ha goal declarado.
 */
public interface NlpWorkerClient {

    /**
     * Submete o texto ao worker e retorna a analise validada + productivity opt-in (quando ha
     * goal).
     *
     * @param meetingId id da reuniao (correlation id)
     * @param tenantId id do tenant dono
     * @param language ISO 639-1 (ex: "pt-BR"), pode ser null
     * @param transcript texto bruto da transcricao (apos normalizacao do upload)
     * @param tenantContext contexto comercial opcional usado no prompt
     * @param goal objetivo declarado pelo usuario (opt-in). Quando ausente, o worker nao emite
     *     productivity.
     */
    AnalysisResult analyze(
            UUID meetingId,
            UUID tenantId,
            String language,
            String transcript,
            Optional<TenantContext> tenantContext,
            Optional<MeetingGoal> goal);

    LiveAnalyzeResponse analyzeLive(
            String transcriptChunk, String language, WorkerDtos.LiveHighlights previousHighlights);

    /**
     * Resultado combinado: analise sempre presente, productivity opcional (so quando goal foi
     * fornecido E o worker conseguiu calcular).
     */
    record AnalysisResult(MeetingAnalysis analysis, Optional<ProductivityAssessment> productivity) {

        public static AnalysisResult of(MeetingAnalysis analysis) {
            return new AnalysisResult(analysis, Optional.empty());
        }

        public static AnalysisResult of(MeetingAnalysis analysis, ProductivityAssessment p) {
            return new AnalysisResult(analysis, Optional.ofNullable(p));
        }
    }
}
