package br.com.nora.api.domain.analysis;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Resultado da analise NLP de uma reuniao (1-1 com Meeting). Imutavel.
 *
 * <p>Espelha o schema canonico em {@code docs/api/llm-schemas/meeting-analysis-v1.schema.json}.
 * Carregado pelo backend a partir do response do worker.
 */
public final class MeetingAnalysis {

    private static final int SUMMARY_MIN = 1;
    private static final int SUMMARY_MAX = 4000;

    private final UUID id;
    private final UUID meetingId;
    private final UUID tenantId;
    private final String summary;
    private final Sentiment sentimentOverall;
    private final List<String> topics;
    private final List<Decision> decisions;
    private final List<ActionItem> actionItems;
    private final List<Risk> risks;
    private final List<Opportunity> opportunities;
    private final String modelVersion;
    private final String promptVersion;
    private final int tokensInput;
    private final int tokensOutput;
    private final int processingMillis;
    private final int piiRedactionsApplied;
    private final OffsetDateTime generatedAt;
    private final OffsetDateTime createdAt;

    public MeetingAnalysis(
            UUID id,
            UUID meetingId,
            UUID tenantId,
            String summary,
            Sentiment sentimentOverall,
            List<String> topics,
            List<Decision> decisions,
            List<ActionItem> actionItems,
            List<Risk> risks,
            List<Opportunity> opportunities,
            String modelVersion,
            String promptVersion,
            int tokensInput,
            int tokensOutput,
            int processingMillis,
            int piiRedactionsApplied,
            OffsetDateTime generatedAt,
            OffsetDateTime createdAt) {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (meetingId == null) {
            throw new IllegalArgumentException("meetingId is required");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("summary is required");
        }
        if (summary.length() < SUMMARY_MIN || summary.length() > SUMMARY_MAX) {
            throw new IllegalArgumentException(
                    "summary length must be between " + SUMMARY_MIN + " and " + SUMMARY_MAX);
        }
        if (sentimentOverall == null) {
            throw new IllegalArgumentException("sentimentOverall is required");
        }
        if (tokensInput < 0 || tokensOutput < 0) {
            throw new IllegalArgumentException("token counts must be non-negative");
        }
        if (processingMillis < 0) {
            throw new IllegalArgumentException("processingMillis must be non-negative");
        }
        if (piiRedactionsApplied < 0) {
            throw new IllegalArgumentException("piiRedactionsApplied must be non-negative");
        }
        this.id = id;
        this.meetingId = meetingId;
        this.tenantId = tenantId;
        this.summary = summary;
        this.sentimentOverall = sentimentOverall;
        this.topics = freeze(topics);
        this.decisions = freeze(decisions);
        this.actionItems = freeze(actionItems);
        this.risks = freeze(risks);
        this.opportunities = freeze(opportunities);
        this.modelVersion = modelVersion;
        this.promptVersion = promptVersion;
        this.tokensInput = tokensInput;
        this.tokensOutput = tokensOutput;
        this.processingMillis = processingMillis;
        this.piiRedactionsApplied = piiRedactionsApplied;
        this.generatedAt = generatedAt == null ? OffsetDateTime.now() : generatedAt;
        this.createdAt = createdAt == null ? this.generatedAt : createdAt;
    }

    /** Factory para criar uma analise nova (id gerado, createdAt = now). */
    public static MeetingAnalysis newAnalysis(
            UUID meetingId,
            UUID tenantId,
            String summary,
            Sentiment sentimentOverall,
            List<String> topics,
            List<Decision> decisions,
            List<ActionItem> actionItems,
            List<Risk> risks,
            List<Opportunity> opportunities,
            String modelVersion,
            String promptVersion,
            int tokensInput,
            int tokensOutput,
            int processingMillis,
            int piiRedactionsApplied) {
        OffsetDateTime now = OffsetDateTime.now();
        return new MeetingAnalysis(
                UUID.randomUUID(),
                meetingId,
                tenantId,
                summary,
                sentimentOverall,
                topics,
                decisions,
                actionItems,
                risks,
                opportunities,
                modelVersion,
                promptVersion,
                tokensInput,
                tokensOutput,
                processingMillis,
                piiRedactionsApplied,
                now,
                now);
    }

    private static <T> List<T> freeze(List<T> input) {
        return input == null ? List.of() : Collections.unmodifiableList(new ArrayList<>(input));
    }

    public UUID id() {
        return id;
    }

    public UUID meetingId() {
        return meetingId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String summary() {
        return summary;
    }

    public Sentiment sentimentOverall() {
        return sentimentOverall;
    }

    public List<String> topics() {
        return topics;
    }

    public List<Decision> decisions() {
        return decisions;
    }

    public List<ActionItem> actionItems() {
        return actionItems;
    }

    public List<Risk> risks() {
        return risks;
    }

    public List<Opportunity> opportunities() {
        return opportunities;
    }

    public String modelVersion() {
        return modelVersion;
    }

    public String promptVersion() {
        return promptVersion;
    }

    public int tokensInput() {
        return tokensInput;
    }

    public int tokensOutput() {
        return tokensOutput;
    }

    public int processingMillis() {
        return processingMillis;
    }

    public int piiRedactionsApplied() {
        return piiRedactionsApplied;
    }

    public OffsetDateTime generatedAt() {
        return generatedAt;
    }

    public OffsetDateTime createdAt() {
        return createdAt;
    }

    /** Snippet curto (ate 240 chars) extraido do inicio do summary, sem markdown. */
    public String summarySnippet() {
        String clean = summary.replaceAll("[#*_`>-]", " ").replaceAll("\\s+", " ").trim();
        return clean.length() <= 240 ? clean : clean.substring(0, 237) + "...";
    }
}
