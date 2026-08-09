package br.com.nora.api.infrastructure.persistence.analysis;

import br.com.nora.api.application.ports.MeetingAnalysisRepository;
import br.com.nora.api.domain.analysis.ActionItem;
import br.com.nora.api.domain.analysis.ActionItemStatus;
import br.com.nora.api.domain.analysis.Decision;
import br.com.nora.api.domain.analysis.MeetingAnalysis;
import br.com.nora.api.domain.analysis.Opportunity;
import br.com.nora.api.domain.analysis.OpportunityCategory;
import br.com.nora.api.domain.analysis.Priority;
import br.com.nora.api.domain.analysis.Risk;
import br.com.nora.api.domain.analysis.RiskCategory;
import br.com.nora.api.domain.analysis.Sentiment;
import br.com.nora.api.domain.analysis.Severity;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MeetingAnalysisRepositoryAdapter implements MeetingAnalysisRepository {

    private final MeetingAnalysisJpaRepository jpa;

    public MeetingAnalysisRepositoryAdapter(MeetingAnalysisJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional
    public MeetingAnalysis save(MeetingAnalysis analysis) {
        // Idempotent reprocessing: if an analysis already exists for the meeting, delete it before
        // saving.
        jpa.findByMeetingIdAndTenantId(analysis.meetingId(), analysis.tenantId())
                .ifPresent(jpa::delete);
        jpa.flush();

        MeetingAnalysisJpaEntity e = toEntity(analysis);
        MeetingAnalysisJpaEntity saved = jpa.save(e);
        return toDomain(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MeetingAnalysis> findByMeetingId(UUID meetingId, UUID tenantId) {
        return jpa.findByMeetingIdAndTenantId(meetingId, tenantId).map(this::toDomain);
    }

    @Override
    @Transactional
    public void deleteByMeetingId(UUID meetingId, UUID tenantId) {
        jpa.deleteByMeetingIdAndTenantId(meetingId, tenantId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AnalysisCounts> countsByMeetingIds(
            java.util.Collection<UUID> meetingIds, UUID tenantId) {
        if (meetingIds == null || meetingIds.isEmpty()) {
            return List.of();
        }
        return jpa.aggregateCountsByMeetingIds(meetingIds, tenantId).stream()
                .map(
                        r ->
                                new AnalysisCounts(
                                        (UUID) r[0],
                                        ((Number) r[1]).intValue(),
                                        ((Number) r[2]).intValue(),
                                        ((Number) r[3]).intValue()))
                .toList();
    }

    private MeetingAnalysisJpaEntity toEntity(MeetingAnalysis a) {
        MeetingAnalysisJpaEntity e = new MeetingAnalysisJpaEntity();
        e.setId(a.id());
        e.setMeetingId(a.meetingId());
        e.setTenantId(a.tenantId());
        e.setSummary(a.summary());
        e.setSentimentOverall(a.sentimentOverall().name());
        e.setTopics(a.topics().toArray(new String[0]));
        e.setModelVersion(a.modelVersion());
        e.setPromptVersion(a.promptVersion());
        e.setTokensInput(a.tokensInput());
        e.setTokensOutput(a.tokensOutput());
        e.setProcessingMillis(a.processingMillis());
        e.setPiiRedactionsApplied(a.piiRedactionsApplied());
        e.setGeneratedAt(a.generatedAt());
        e.setCreatedAt(a.createdAt());

        OffsetDateTime now = OffsetDateTime.now();

        for (int i = 0; i < a.decisions().size(); i++) {
            Decision d = a.decisions().get(i);
            DecisionJpaEntity de = new DecisionJpaEntity();
            de.setId(UUID.randomUUID());
            de.setAnalysisId(a.id());
            de.setTenantId(a.tenantId());
            de.setText(d.text());
            de.setConfidence(BigDecimal.valueOf(d.confidence()).setScale(2, RoundingMode.HALF_UP));
            de.setPosition(i);
            de.setCreatedAt(now);
            e.getDecisions().add(de);
        }
        for (int i = 0; i < a.actionItems().size(); i++) {
            ActionItem ai = a.actionItems().get(i);
            ActionItemJpaEntity ae = new ActionItemJpaEntity();
            ae.setId(UUID.randomUUID());
            ae.setAnalysisId(a.id());
            ae.setTenantId(a.tenantId());
            ae.setTitle(ai.title());
            ae.setAssignee(ai.assignee());
            ae.setDueDate(ai.dueDate());
            ae.setPriority(ai.priority().name());
            ae.setSourceQuote(ai.sourceQuote());
            ae.setStatus(ai.status().name());
            ae.setPosition(i);
            ae.setCreatedAt(now);
            ae.setUpdatedAt(now);
            e.getActionItems().add(ae);
        }
        for (int i = 0; i < a.risks().size(); i++) {
            Risk r = a.risks().get(i);
            RiskJpaEntity re = new RiskJpaEntity();
            re.setId(UUID.randomUUID());
            re.setAnalysisId(a.id());
            re.setTenantId(a.tenantId());
            re.setText(r.text());
            re.setSeverity(r.severity().name());
            re.setCategory(r.category().name());
            re.setSourceQuote(r.sourceQuote());
            re.setPosition(i);
            re.setCreatedAt(now);
            e.getRisks().add(re);
        }
        for (int i = 0; i < a.opportunities().size(); i++) {
            Opportunity o = a.opportunities().get(i);
            OpportunityJpaEntity oe = new OpportunityJpaEntity();
            oe.setId(UUID.randomUUID());
            oe.setAnalysisId(a.id());
            oe.setTenantId(a.tenantId());
            oe.setText(o.text());
            oe.setEstimatedValue(o.estimatedValue().name());
            oe.setCategory(o.category().name());
            oe.setSourceQuote(o.sourceQuote());
            oe.setPosition(i);
            oe.setCreatedAt(now);
            e.getOpportunities().add(oe);
        }
        return e;
    }

    private MeetingAnalysis toDomain(MeetingAnalysisJpaEntity e) {
        List<Decision> decisions =
                e.getDecisions().stream()
                        .map(d -> new Decision(d.getText(), d.getConfidence().doubleValue()))
                        .toList();
        List<ActionItem> actionItems =
                e.getActionItems().stream()
                        .map(
                                ai ->
                                        new ActionItem(
                                                ai.getTitle(),
                                                ai.getAssignee(),
                                                ai.getDueDate(),
                                                Priority.valueOf(ai.getPriority()),
                                                ai.getSourceQuote(),
                                                ActionItemStatus.valueOf(ai.getStatus())))
                        .toList();
        List<Risk> risks =
                e.getRisks().stream()
                        .map(
                                r ->
                                        new Risk(
                                                r.getText(),
                                                Severity.valueOf(r.getSeverity()),
                                                RiskCategory.valueOf(r.getCategory()),
                                                r.getSourceQuote()))
                        .toList();
        List<Opportunity> opportunities =
                e.getOpportunities().stream()
                        .map(
                                o ->
                                        new Opportunity(
                                                o.getText(),
                                                Severity.valueOf(o.getEstimatedValue()),
                                                OpportunityCategory.valueOf(o.getCategory()),
                                                o.getSourceQuote()))
                        .toList();
        return new MeetingAnalysis(
                e.getId(),
                e.getMeetingId(),
                e.getTenantId(),
                e.getSummary(),
                Sentiment.valueOf(e.getSentimentOverall()),
                List.of(e.getTopics()),
                decisions,
                actionItems,
                risks,
                opportunities,
                e.getModelVersion(),
                e.getPromptVersion(),
                e.getTokensInput(),
                e.getTokensOutput(),
                e.getProcessingMillis(),
                e.getPiiRedactionsApplied(),
                e.getGeneratedAt(),
                e.getCreatedAt());
    }
}
