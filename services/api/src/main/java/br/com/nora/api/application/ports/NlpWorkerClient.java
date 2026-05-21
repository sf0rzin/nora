package br.com.nora.api.application.ports;

import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.customer.BuyingSignal;
import br.com.nora.api.domain.customer.ConfidenceBand;
import br.com.nora.api.domain.customer.ConfidenceTrend;
import br.com.nora.api.domain.customer.Objection;
import br.com.nora.api.domain.meeting.productivity.MeetingGoal;
import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import br.com.nora.api.domain.tenant.TenantContext;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import br.com.nora.api.infrastructure.nlp.WorkerDtos.LiveAnalyzeResponse;
import java.util.List;
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
     * fornecido E o worker conseguiu calcular) e customerConfidence opcional (so para reunioes
     * externas, quando o worker emite o bloco).
     */
    record AnalysisResult(
            MeetingAnalysis analysis,
            Optional<ProductivityAssessment> productivity,
            Optional<CustomerConfidenceCarrier> customerConfidence) {

        public static AnalysisResult of(MeetingAnalysis analysis) {
            return new AnalysisResult(analysis, Optional.empty(), Optional.empty());
        }

        public static AnalysisResult of(MeetingAnalysis analysis, ProductivityAssessment p) {
            return new AnalysisResult(analysis, Optional.ofNullable(p), Optional.empty());
        }

        public static AnalysisResult of(
                MeetingAnalysis analysis,
                ProductivityAssessment p,
                CustomerConfidenceCarrier confidence) {
            return new AnalysisResult(
                    analysis, Optional.ofNullable(p), Optional.ofNullable(confidence));
        }
    }

    /**
     * Carrier intermediario do bloco {@code customerConfidence} do worker (ADR 0015). NAO e o
     * agregado de dominio: a {@code CustomerConfidenceAssessment} exige um {@code
     * customerAccountId} que so existe depois do get-or-create da {@link
     * br.com.nora.api.domain.customer.CustomerAccount} no backend. O service de aplicacao resolve a
     * conta a partir de {@code accountName} e so entao constroi o agregado.
     *
     * <p>{@code trend} aqui e o palpite do worker — o backend o IGNORA e recalcula server-side com
     * base no historico da conta (backend e autoritativo, ver ADR 0015 / schema).
     *
     * @param accountName nome do cliente/lead detectado (null/blank => reuniao interna, no-op)
     */
    record CustomerConfidenceCarrier(
            String accountName,
            int score,
            ConfidenceBand band,
            ConfidenceTrend trend,
            List<BuyingSignal> buyingSignals,
            List<Objection> objections,
            String rationale) {

        public CustomerConfidenceCarrier {
            buyingSignals = buyingSignals == null ? List.of() : List.copyOf(buyingSignals);
            objections = objections == null ? List.of() : List.copyOf(objections);
        }
    }
}
