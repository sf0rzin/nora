package br.com.nora.api.application.customer;

import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.ports.CustomerAccountRepository;
import br.com.nora.api.application.ports.CustomerConfidenceAssessmentRepository;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.NlpWorkerClient.CustomerConfidenceCarrier;
import br.com.nora.api.domain.customer.ConfidenceTrend;
import br.com.nora.api.domain.customer.CustomerAccount;
import br.com.nora.api.domain.customer.CustomerConfidenceAssessment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persiste o Customer Confidence emitido pelo worker (ADR 0015): resolve a conta de cliente
 * (get-or-create por nome, dedup case-insensitive), vincula reuniao&lt;-&gt;conta, calcula o trend
 * server-side (backend autoritativo) e grava a avaliacao + sinais + objecoes.
 *
 * <p>Sempre escopado por {@code tenantId} (ADR 0002). Reunioes internas (sem {@code accountName})
 * sao no-op: nenhuma conta criada.
 */
@Service
public class CustomerConfidenceService {

    private static final Logger LOG = LoggerFactory.getLogger(CustomerConfidenceService.class);

    /**
     * Banda morta do trend: variacoes de score dentro de +/- {@value} pontos sao consideradas
     * estaveis (STABLE), evitando ruido de pequenas oscilacoes entre reunioes.
     */
    private static final int TREND_STABLE_BAND = 5;

    private final CustomerAccountRepository accounts;
    private final CustomerConfidenceAssessmentRepository assessments;
    private final MeetingRepository meetings;

    public CustomerConfidenceService(
            CustomerAccountRepository accounts,
            CustomerConfidenceAssessmentRepository assessments,
            MeetingRepository meetings) {
        this.accounts = accounts;
        this.assessments = assessments;
        this.meetings = meetings;
    }

    /**
     * Persiste o carrier do worker. No-op quando {@code accountName} e null/blank (reuniao
     * interna). Caso contrario: get-or-create da conta, link idempotente reuniao&lt;-&gt;conta,
     * trend recalculado e avaliacao gravada.
     *
     * @return a avaliacao persistida, ou vazio quando foi no-op
     */
    @Transactional
    public Optional<CustomerConfidenceAssessment> persist(
            UUID tenantId, UUID meetingId, CustomerConfidenceCarrier carrier) {
        if (carrier == null || carrier.accountName() == null || carrier.accountName().isBlank()) {
            // Reuniao interna: o worker nao identificou cliente. Nenhuma conta/avaliacao criada.
            return Optional.empty();
        }

        String accountName = carrier.accountName().trim();
        CustomerAccount account = getOrCreateAccount(tenantId, accountName);
        accounts.linkMeeting(meetingId, account.id(), tenantId);

        ConfidenceTrend trend = computeTrend(tenantId, meetingId, account.id(), carrier.score());

        CustomerConfidenceAssessment assessment =
                CustomerConfidenceAssessment.newAssessment(
                        tenantId,
                        meetingId,
                        account.id(),
                        carrier.score(),
                        carrier.band(),
                        trend,
                        carrier.buyingSignals(),
                        carrier.objections(),
                        carrier.rationale());
        CustomerConfidenceAssessment saved = assessments.save(assessment);
        LOG.info(
                "Customer confidence persisted meetingId={} accountId={} score={} trend={}",
                meetingId,
                account.id(),
                saved.score(),
                saved.trend());
        return Optional.of(saved);
    }

    /** Recupera as avaliacoes da reuniao (no maximo uma por conta). Escopado por tenant. */
    @Transactional(readOnly = true)
    public List<CustomerConfidenceAssessment> findByMeetingId(UUID meetingId, UUID tenantId) {
        meetings.findByIdAndTenant(meetingId, tenantId).orElseThrow(MeetingException.NotFound::new);
        return assessments.findByMeetingId(meetingId, tenantId);
    }

    /**
     * View de leitura para o detalhe da reuniao: cada avaliacao ja com o nome da conta resolvido (o
     * agregado de dominio so guarda {@code customerAccountId}). Mantem a resolucao do nome na
     * camada de aplicacao, deixando o controller fino. Escopado por tenant.
     */
    @Transactional(readOnly = true)
    public List<ConfidenceView> findViewByMeetingId(UUID meetingId, UUID tenantId) {
        meetings.findByIdAndTenant(meetingId, tenantId).orElseThrow(MeetingException.NotFound::new);
        return assessments.findByMeetingId(meetingId, tenantId).stream()
                .map(
                        a -> {
                            String accountName =
                                    accounts.findById(a.customerAccountId(), tenantId)
                                            .map(CustomerAccount::name)
                                            .orElse(null);
                            return new ConfidenceView(a, accountName);
                        })
                .toList();
    }

    /** Avaliacao + nome da conta resolvido, para projecao na API. */
    public record ConfidenceView(CustomerConfidenceAssessment assessment, String accountName) {}

    private CustomerAccount getOrCreateAccount(UUID tenantId, String name) {
        return accounts.findByTenantAndLowerName(tenantId, name)
                .orElseGet(() -> accounts.save(CustomerAccount.create(tenantId, name, null, null)));
    }

    /**
     * Trend autoritativo do backend: compara o score novo com o da ultima reuniao anterior da mesma
     * conta. Sem historico (primeira reuniao da conta) =&gt; null. O palpite do worker e ignorado.
     */
    private ConfidenceTrend computeTrend(
            UUID tenantId, UUID meetingId, UUID accountId, int newScore) {
        return assessments
                .findLatestByAccountId(accountId, meetingId, tenantId)
                .map(
                        prior -> {
                            int delta = newScore - prior.score();
                            if (delta > TREND_STABLE_BAND) {
                                return ConfidenceTrend.IMPROVING;
                            }
                            if (delta < -TREND_STABLE_BAND) {
                                return ConfidenceTrend.DECLINING;
                            }
                            return ConfidenceTrend.STABLE;
                        })
                .orElse(null);
    }
}
