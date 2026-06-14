package br.com.nora.api.infrastructure.persistence.analysis;

import br.com.nora.api.application.ports.ProductivityAssessmentRepository;
import br.com.nora.api.domain.meeting.productivity.CoverageStatus;
import br.com.nora.api.domain.meeting.productivity.OutcomeCoverage;
import br.com.nora.api.domain.meeting.productivity.ProductivityAssessment;
import br.com.nora.api.domain.meeting.productivity.ProductivityBand;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ProductivityAssessmentRepositoryAdapter implements ProductivityAssessmentRepository {

    private final ProductivityAssessmentJpaRepository jpa;

    public ProductivityAssessmentRepositoryAdapter(ProductivityAssessmentJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public ProductivityAssessment save(ProductivityAssessment assessment) {
        // Idempotente: substitui assessment existente do mesmo meeting/tenant. Usamos DELETE
        // nativo para que o ON DELETE CASCADE do Postgres limpe as coverages sem o ciclo
        // problematico de UPDATE SET NULL que o Hibernate tentaria via orphanRemoval.
        jpa.deleteByMeetingIdAndTenantIdNative(assessment.meetingId(), assessment.tenantId());
        jpa.flush();
        ProductivityAssessmentJpaEntity entity = toEntity(assessment);
        ProductivityAssessmentJpaEntity saved = jpa.save(entity);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductivityAssessment> findByMeetingId(UUID meetingId, UUID tenantId) {
        return jpa.findByMeetingIdAndTenantId(meetingId, tenantId).map(this::toDomain);
    }

    @Override
    @Transactional
    public void deleteByMeetingId(UUID meetingId, UUID tenantId) {
        // DELETE nativo para acionar ON DELETE CASCADE do Postgres direto, sem o ciclo
        // UPDATE SET NULL que o Hibernate tentaria com @JoinColumn unidirecional + orphanRemoval.
        jpa.deleteByMeetingIdAndTenantIdNative(meetingId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BandScore> bandsByMeetingIds(java.util.Collection<UUID> meetingIds, UUID tenantId) {
        if (meetingIds == null || meetingIds.isEmpty()) {
            return List.of();
        }
        return jpa.aggregateBandsByMeetingIds(meetingIds, tenantId).stream()
                .map(r -> new BandScore((UUID) r[0], (String) r[1], ((Number) r[2]).intValue()))
                .toList();
    }

    private ProductivityAssessmentJpaEntity toEntity(ProductivityAssessment a) {
        ProductivityAssessmentJpaEntity e = new ProductivityAssessmentJpaEntity();
        e.setId(a.id());
        e.setTenantId(a.tenantId());
        e.setMeetingId(a.meetingId());
        e.setScore(a.score());
        e.setBand(a.band().name());
        e.setOffTopicRatio(toScaledBigDecimal(a.offTopicRatio()));
        e.setDecisionDensity(toScaledBigDecimal(a.decisionDensity()));
        e.setRationale(a.rationale());
        e.setCreatedAt(a.createdAt());
        for (OutcomeCoverage cov : a.coverage()) {
            OutcomeCoverageJpaEntity ce = new OutcomeCoverageJpaEntity();
            ce.setId(UUID.randomUUID());
            ce.setAssessmentId(a.id());
            ce.setExpectedOutcome(cov.expectedOutcome());
            ce.setStatus(cov.status().name());
            ce.setEvidence(cov.evidence());
            ce.setPosition(cov.position());
            e.getCoverage().add(ce);
        }
        return e;
    }

    private ProductivityAssessment toDomain(ProductivityAssessmentJpaEntity e) {
        List<OutcomeCoverage> coverage =
                e.getCoverage().stream()
                        .map(
                                c ->
                                        new OutcomeCoverage(
                                                c.getExpectedOutcome(),
                                                CoverageStatus.valueOf(c.getStatus()),
                                                c.getEvidence(),
                                                c.getPosition()))
                        .toList();
        return new ProductivityAssessment(
                e.getId(),
                e.getTenantId(),
                e.getMeetingId(),
                e.getScore(),
                ProductivityBand.valueOf(e.getBand()),
                coverage,
                e.getOffTopicRatio() == null ? null : e.getOffTopicRatio().doubleValue(),
                e.getDecisionDensity() == null ? null : e.getDecisionDensity().doubleValue(),
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
