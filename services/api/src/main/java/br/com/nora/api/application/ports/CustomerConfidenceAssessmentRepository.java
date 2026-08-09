package br.com.nora.api.application.ports;

import br.com.nora.api.domain.customer.CustomerConfidenceAssessment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Customer Confidence Assessment persistence (ADR 0015). Always scoped by tenant.
 *
 * <p>1-1 per (meeting, account) pair: a meeting can touch more than one account, so {@code
 * findByMeetingId} returns a list (at most one assessment per account).
 */
public interface CustomerConfidenceAssessmentRepository {

    CustomerConfidenceAssessment save(CustomerConfidenceAssessment assessment);

    List<CustomerConfidenceAssessment> findByMeetingId(UUID meetingId, UUID tenantId);

    /**
     * Most recent assessment (by {@code created_at} desc) of an account, excluding the given
     * meeting. Used to compute the {@code trend} server-side: compares the new score with the one
     * from the account's previous meeting. Empty when it is the account's first meeting.
     *
     * @param accountId target account
     * @param excludeMeetingId current meeting (excluded to tolerate reprocessing)
     * @param tenantId owning tenant (filtered before the id)
     */
    Optional<CustomerConfidenceAssessment> findLatestByAccountId(
            UUID accountId, UUID excludeMeetingId, UUID tenantId);
}
