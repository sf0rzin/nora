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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "meeting_analyses")
public class MeetingAnalysisJpaEntity {

    @Id private UUID id;

    @Column(name = "meeting_id", nullable = false, unique = true)
    private UUID meetingId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column(name = "sentiment_overall", nullable = false)
    private String sentimentOverall;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "topics", nullable = false, columnDefinition = "text[]")
    private String[] topics = new String[0];

    @Column(name = "model_version")
    private String modelVersion;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "tokens_input", nullable = false)
    private int tokensInput;

    @Column(name = "tokens_output", nullable = false)
    private int tokensOutput;

    @Column(name = "processing_millis", nullable = false)
    private int processingMillis;

    @Column(name = "pii_redactions_applied", nullable = false)
    private int piiRedactionsApplied;

    @Column(name = "generated_at", nullable = false)
    private OffsetDateTime generatedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    // `insertable = false, updatable = false` on all four is what makes REPROCESSING work, and it
    // is not cosmetic.
    //
    // These are UNIDIRECTIONAL @OneToMany with @JoinColumn, and the CHILD already maps the same
    // column itself (`DecisionJpaEntity.analysisId` and friends), which the adapter sets on every
    // row. Hibernate therefore had a writable association column that nothing needed it to write —
    // and on delete it used that write access to DISSOCIATE the children first:
    //
    //   update meeting_action_items set analysis_id=null where analysis_id=?
    //
    // Every one of these columns is `NOT NULL REFERENCES ... ON DELETE CASCADE` (V005, V012), so
    // that UPDATE cannot succeed:
    //
    //   ERROR: null value in column "analysis_id" of relation "meeting_action_items"
    //          violates not-null constraint
    //
    // `MeetingAnalysisRepositoryAdapter.save` deletes any existing analysis before writing the new
    // one, so this fired on EVERY re-analysis of a meeting that already had action items — which
    // is every analysed meeting. Reprocess failed, and so did setting a goal on an analysed
    // meeting, because `MeetingGoalService` re-queues the analysis to compute the Productivity
    // Score. ADR 0005's whole feature was unreachable in production as a result.
    //
    // Making the association read-only removes the dissociation entirely: Hibernate cannot null a
    // column it may not write, so orphan removal deletes the rows, which is what the database's
    // own ON DELETE CASCADE would have done. The FK still gets written on insert — by the child,
    // which owns it.
    //
    // NOT `nullable = false`: that leaves the column writable, and because the child maps it too
    // Hibernate rejects the entity outright with "Column 'analysis_id' is duplicated in mapping".
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "analysis_id", insertable = false, updatable = false)
    @OrderBy("position ASC")
    private List<DecisionJpaEntity> decisions = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "analysis_id", insertable = false, updatable = false)
    @OrderBy("position ASC")
    private List<ActionItemJpaEntity> actionItems = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "analysis_id", insertable = false, updatable = false)
    @OrderBy("position ASC")
    private List<RiskJpaEntity> risks = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "analysis_id", insertable = false, updatable = false)
    @OrderBy("position ASC")
    private List<OpportunityJpaEntity> opportunities = new ArrayList<>();

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getMeetingId() {
        return meetingId;
    }

    public void setMeetingId(UUID meetingId) {
        this.meetingId = meetingId;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSentimentOverall() {
        return sentimentOverall;
    }

    public void setSentimentOverall(String sentimentOverall) {
        this.sentimentOverall = sentimentOverall;
    }

    public String[] getTopics() {
        return topics;
    }

    public void setTopics(String[] topics) {
        this.topics = topics;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public void setModelVersion(String modelVersion) {
        this.modelVersion = modelVersion;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public void setPromptVersion(String promptVersion) {
        this.promptVersion = promptVersion;
    }

    public int getTokensInput() {
        return tokensInput;
    }

    public void setTokensInput(int tokensInput) {
        this.tokensInput = tokensInput;
    }

    public int getTokensOutput() {
        return tokensOutput;
    }

    public void setTokensOutput(int tokensOutput) {
        this.tokensOutput = tokensOutput;
    }

    public int getProcessingMillis() {
        return processingMillis;
    }

    public void setProcessingMillis(int processingMillis) {
        this.processingMillis = processingMillis;
    }

    public int getPiiRedactionsApplied() {
        return piiRedactionsApplied;
    }

    public void setPiiRedactionsApplied(int piiRedactionsApplied) {
        this.piiRedactionsApplied = piiRedactionsApplied;
    }

    public OffsetDateTime getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(OffsetDateTime generatedAt) {
        this.generatedAt = generatedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<DecisionJpaEntity> getDecisions() {
        return decisions;
    }

    public void setDecisions(List<DecisionJpaEntity> decisions) {
        this.decisions = decisions;
    }

    public List<ActionItemJpaEntity> getActionItems() {
        return actionItems;
    }

    public void setActionItems(List<ActionItemJpaEntity> actionItems) {
        this.actionItems = actionItems;
    }

    public List<RiskJpaEntity> getRisks() {
        return risks;
    }

    public void setRisks(List<RiskJpaEntity> risks) {
        this.risks = risks;
    }

    public List<OpportunityJpaEntity> getOpportunities() {
        return opportunities;
    }

    public void setOpportunities(List<OpportunityJpaEntity> opportunities) {
        this.opportunities = opportunities;
    }
}
