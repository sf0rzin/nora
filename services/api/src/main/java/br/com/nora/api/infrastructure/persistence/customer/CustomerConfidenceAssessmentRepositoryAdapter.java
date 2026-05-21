package br.com.nora.api.infrastructure.persistence.customer;

import br.com.nora.api.application.ports.CustomerConfidenceAssessmentRepository;
import br.com.nora.api.domain.customer.BuyingSignal;
import br.com.nora.api.domain.customer.BuyingSignalType;
import br.com.nora.api.domain.customer.ConfidenceBand;
import br.com.nora.api.domain.customer.ConfidenceTrend;
import br.com.nora.api.domain.customer.CustomerConfidenceAssessment;
import br.com.nora.api.domain.customer.Objection;
import br.com.nora.api.domain.customer.ObjectionSeverity;
import br.com.nora.api.domain.customer.ObjectionType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CustomerConfidenceAssessmentRepositoryAdapter
        implements CustomerConfidenceAssessmentRepository {

    private final CustomerConfidenceAssessmentJpaRepository jpa;

    public CustomerConfidenceAssessmentRepositoryAdapter(
            CustomerConfidenceAssessmentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public CustomerConfidenceAssessment save(CustomerConfidenceAssessment assessment) {
        // Idempotente: substitui assessment existente do mesmo par (meeting, account). DELETE
        // nativo
        // para que o ON DELETE CASCADE do Postgres limpe signals/objections sem o ciclo de UPDATE
        // SET
        // NULL que o Hibernate tentaria via orphanRemoval+@JoinColumn unidirecional.
        jpa.deleteByMeetingAndAccountNative(
                assessment.meetingId(), assessment.customerAccountId(), assessment.tenantId());
        jpa.flush();
        CustomerConfidenceAssessmentJpaEntity entity = toEntity(assessment);
        CustomerConfidenceAssessmentJpaEntity saved = jpa.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerConfidenceAssessment> findByMeetingId(UUID meetingId, UUID tenantId) {
        return jpa.findByMeetingIdAndTenantId(meetingId, tenantId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerConfidenceAssessment> findLatestByAccountId(
            UUID accountId, UUID excludeMeetingId, UUID tenantId) {
        return jpa
                .findByCustomerAccountIdAndTenantIdAndMeetingIdNotOrderByCreatedAtDesc(
                        accountId, tenantId, excludeMeetingId)
                .stream()
                .findFirst()
                .map(this::toDomain);
    }

    private CustomerConfidenceAssessmentJpaEntity toEntity(CustomerConfidenceAssessment a) {
        CustomerConfidenceAssessmentJpaEntity e = new CustomerConfidenceAssessmentJpaEntity();
        e.setId(a.id());
        e.setTenantId(a.tenantId());
        e.setMeetingId(a.meetingId());
        e.setCustomerAccountId(a.customerAccountId());
        e.setScore(a.score());
        e.setBand(a.band().name());
        e.setTrend(a.trend() == null ? null : a.trend().name());
        e.setRationale(a.rationale());
        e.setCreatedAt(a.createdAt());
        for (BuyingSignal signal : a.buyingSignals()) {
            BuyingSignalJpaEntity se = new BuyingSignalJpaEntity();
            se.setId(UUID.randomUUID());
            se.setAssessmentId(a.id());
            se.setType(signal.type().name());
            se.setQuote(signal.quote());
            se.setWeight(toScaledBigDecimal(signal.weight()));
            se.setPosition(signal.position());
            e.getBuyingSignals().add(se);
        }
        for (Objection objection : a.objections()) {
            ObjectionJpaEntity oe = new ObjectionJpaEntity();
            oe.setId(UUID.randomUUID());
            oe.setAssessmentId(a.id());
            oe.setType(objection.type().name());
            oe.setQuote(objection.quote());
            oe.setSeverity(objection.severity().name());
            oe.setCompetitor(objection.competitor());
            oe.setPosition(objection.position());
            e.getObjections().add(oe);
        }
        return e;
    }

    private CustomerConfidenceAssessment toDomain(CustomerConfidenceAssessmentJpaEntity e) {
        List<BuyingSignal> signals =
                e.getBuyingSignals().stream()
                        .map(
                                s ->
                                        new BuyingSignal(
                                                BuyingSignalType.valueOf(s.getType()),
                                                s.getQuote(),
                                                s.getWeight() == null
                                                        ? null
                                                        : s.getWeight().doubleValue(),
                                                s.getPosition()))
                        .toList();
        List<Objection> objections =
                e.getObjections().stream()
                        .map(
                                o ->
                                        new Objection(
                                                ObjectionType.valueOf(o.getType()),
                                                o.getQuote(),
                                                ObjectionSeverity.valueOf(o.getSeverity()),
                                                o.getCompetitor(),
                                                o.getPosition()))
                        .toList();
        return new CustomerConfidenceAssessment(
                e.getId(),
                e.getTenantId(),
                e.getMeetingId(),
                e.getCustomerAccountId(),
                e.getScore(),
                ConfidenceBand.valueOf(e.getBand()),
                e.getTrend() == null ? null : ConfidenceTrend.valueOf(e.getTrend()),
                signals,
                objections,
                e.getRationale(),
                e.getCreatedAt());
    }

    private static BigDecimal toScaledBigDecimal(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
    }
}
