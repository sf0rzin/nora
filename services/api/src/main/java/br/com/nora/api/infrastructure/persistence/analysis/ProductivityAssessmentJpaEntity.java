package br.com.nora.api.infrastructure.persistence.analysis;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "meeting_productivity_assessments")
public class ProductivityAssessmentJpaEntity {

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "meeting_id", nullable = false, unique = true)
    private UUID meetingId;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "band", nullable = false, length = 10)
    private String band;

    @Column(name = "off_topic_ratio", precision = 4, scale = 3)
    private BigDecimal offTopicRatio;

    @Column(name = "decision_density", precision = 4, scale = 3)
    private BigDecimal decisionDensity;

    @Column(name = "rationale", nullable = false, columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // Same unidirectional @OneToMany shape, same reason: `meeting_outcome_coverage.assessment_id`
    // is NOT NULL (V012), so without this Hibernate's dissociation UPDATE fails when an
    // assessment is replaced. See the note on MeetingAnalysisJpaEntity's collections — this one
    // is on the same code path, because a re-analysis rewrites the assessment too.
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "assessment_id", nullable = false)
    @OrderBy("position ASC")
    private List<OutcomeCoverageJpaEntity> coverage = new ArrayList<>();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(UUID meetingId) {
        this.meetingId = meetingId;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public String getBand() {
        return band;
    }

    public void setBand(String band) {
        this.band = band;
    }

    public BigDecimal getOffTopicRatio() {
        return offTopicRatio;
    }

    public void setOffTopicRatio(BigDecimal offTopicRatio) {
        this.offTopicRatio = offTopicRatio;
    }

    public BigDecimal getDecisionDensity() {
        return decisionDensity;
    }

    public void setDecisionDensity(BigDecimal decisionDensity) {
        this.decisionDensity = decisionDensity;
    }

    public String getRationale() {
        return rationale;
    }

    public void setRationale(String rationale) {
        this.rationale = rationale;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<OutcomeCoverageJpaEntity> getCoverage() {
        return coverage;
    }

    public void setCoverage(List<OutcomeCoverageJpaEntity> coverage) {
        this.coverage = coverage;
    }
}
