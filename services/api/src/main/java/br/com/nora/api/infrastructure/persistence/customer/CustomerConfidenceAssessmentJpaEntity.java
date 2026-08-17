package br.com.nora.api.infrastructure.persistence.customer;

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

@Entity
@Table(name = "customer_confidence_assessments")
public class CustomerConfidenceAssessmentJpaEntity {

    @Id private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "customer_account_id", nullable = false)
    private UUID customerAccountId;

    @Column(name = "score", nullable = false)
    private int score;

    @Column(name = "band", nullable = false, length = 10)
    private String band;

    @Column(name = "trend", length = 10)
    private String trend;

    @Column(name = "rationale", nullable = false, columnDefinition = "TEXT")
    private String rationale;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "assessment_id", insertable = false, updatable = false)
    @OrderBy("position ASC")
    private List<BuyingSignalJpaEntity> buyingSignals = new ArrayList<>();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "assessment_id", insertable = false, updatable = false)
    @OrderBy("position ASC")
    private List<ObjectionJpaEntity> objections = new ArrayList<>();

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

    public UUID getCustomerAccountId() {
        return customerAccountId;
    }

    public void setCustomerAccountId(UUID customerAccountId) {
        this.customerAccountId = customerAccountId;
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

    public String getTrend() {
        return trend;
    }

    public void setTrend(String trend) {
        this.trend = trend;
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

    public List<BuyingSignalJpaEntity> getBuyingSignals() {
        return buyingSignals;
    }

    public void setBuyingSignals(List<BuyingSignalJpaEntity> buyingSignals) {
        this.buyingSignals = buyingSignals;
    }

    public List<ObjectionJpaEntity> getObjections() {
        return objections;
    }

    public void setObjections(List<ObjectionJpaEntity> objections) {
        this.objections = objections;
    }
}
