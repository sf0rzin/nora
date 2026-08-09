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
 * Persists the Customer Confidence emitted by the worker (ADR 0015): resolves the customer account
 * (get-or-create by name, case-insensitive dedup), links meeting&lt;-&gt;account, computes the
 * trend server-side (backend is authoritative) and writes the assessment + signals + objections.
 *
 * <p>Always scoped by {@code tenantId} (ADR 0002). Internal meetings (without {@code accountName})
 * are a no-op: no account created.
 */
@Service
public class CustomerConfidenceService {

    private static final Logger LOG = LoggerFactory.getLogger(CustomerConfidenceService.class);

    /**
     * Trend dead band: score variations within +/- {@value} points are considered stable (STABLE),
     * avoiding noise from small oscillations between meetings.
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
     * Persists the worker carrier. No-op when {@code accountName} is null/blank (internal meeting).
     * Otherwise: get-or-create of the account, idempotent meeting&lt;-&gt;account link, recomputed
     * trend and assessment written.
     *
     * @return the persisted assessment, or empty when it was a no-op
     */
    @Transactional
    public Optional<CustomerConfidenceAssessment> persist(
            UUID tenantId, UUID meetingId, CustomerConfidenceCarrier carrier) {
        if (carrier == null || carrier.accountName() == null || carrier.accountName().isBlank()) {
            // Internal meeting: worker did not identify a customer. No account/assessment created.
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

    /** Retrieves the meeting assessments (at most one per account). Scoped by tenant. */
    @Transactional(readOnly = true)
    public List<CustomerConfidenceAssessment> findByMeetingId(UUID meetingId, UUID tenantId) {
        meetings.findByIdAndTenant(meetingId, tenantId).orElseThrow(MeetingException.NotFound::new);
        return assessments.findByMeetingId(meetingId, tenantId);
    }

    /**
     * Read view for the meeting detail: each assessment already with the account name resolved (the
     * domain aggregate only holds {@code customerAccountId}). Keeps the name resolution in the
     * application layer, leaving the controller thin. Scoped by tenant.
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

    /** Assessment + resolved account name, for projection in the API. */
    public record ConfidenceView(CustomerConfidenceAssessment assessment, String accountName) {}

    private CustomerAccount getOrCreateAccount(UUID tenantId, String name) {
        return accounts.findByTenantAndLowerName(tenantId, name)
                .orElseGet(() -> accounts.save(CustomerAccount.create(tenantId, name, null, null)));
    }

    /**
     * Backend-authoritative trend: compares the new score with the one from the last previous
     * meeting of the same account. No history (first meeting of the account) =&gt; null. The
     * worker's guess is ignored.
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
