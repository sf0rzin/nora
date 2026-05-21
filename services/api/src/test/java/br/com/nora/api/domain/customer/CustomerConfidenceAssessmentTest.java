package br.com.nora.api.domain.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerConfidenceAssessmentTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID meeting = UUID.randomUUID();
    private final UUID account = UUID.randomUUID();

    @Test
    void newAssessmentFactoryTrimsRationaleAndDefaultsLists() {
        CustomerConfidenceAssessment assessment =
                CustomerConfidenceAssessment.newAssessment(
                        tenant,
                        meeting,
                        account,
                        72,
                        ConfidenceBand.MEDIUM,
                        ConfidenceTrend.IMPROVING,
                        null,
                        null,
                        "  Cliente engajado, orcamento confirmado.  ");

        assertThat(assessment.id()).isNotNull();
        assertThat(assessment.tenantId()).isEqualTo(tenant);
        assertThat(assessment.meetingId()).isEqualTo(meeting);
        assertThat(assessment.customerAccountId()).isEqualTo(account);
        assertThat(assessment.score()).isEqualTo(72);
        assertThat(assessment.band()).isEqualTo(ConfidenceBand.MEDIUM);
        assertThat(assessment.trend()).isEqualTo(ConfidenceTrend.IMPROVING);
        assertThat(assessment.rationale()).isEqualTo("Cliente engajado, orcamento confirmado.");
        assertThat(assessment.buyingSignals()).isEmpty();
        assertThat(assessment.objections()).isEmpty();
        assertThat(assessment.createdAt()).isNotNull();
    }

    @Test
    void preservesSignalsAndObjectionsAndIsDefensivelyCopied() {
        List<BuyingSignal> signals = new ArrayList<>();
        signals.add(
                new BuyingSignal(
                        BuyingSignalType.BUDGET_DISCUSSED, "Temos verba aprovada", 0.8, 0));
        List<Objection> objections = new ArrayList<>();
        objections.add(
                new Objection(
                        ObjectionType.PRICE,
                        "Esta acima do esperado",
                        ObjectionSeverity.HIGH,
                        null,
                        0));

        CustomerConfidenceAssessment assessment =
                CustomerConfidenceAssessment.newAssessment(
                        tenant,
                        meeting,
                        account,
                        55,
                        ConfidenceBand.MEDIUM,
                        null,
                        signals,
                        objections,
                        "Sinais mistos na reuniao.");

        // Mutating the source lists must not affect the stored assessment.
        signals.clear();
        objections.clear();

        assertThat(assessment.buyingSignals()).hasSize(1);
        assertThat(assessment.objections()).hasSize(1);
        assertThat(assessment.trend()).isNull();

        // Returned lists are unmodifiable.
        assertThatThrownBy(
                        () ->
                                assessment
                                        .buyingSignals()
                                        .add(
                                                new BuyingSignal(
                                                        BuyingSignalType.OTHER, "outro", null, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void scoreOutOfRangeFails() {
        assertThatThrownBy(
                        () ->
                                CustomerConfidenceAssessment.newAssessment(
                                        tenant,
                                        meeting,
                                        account,
                                        101,
                                        ConfidenceBand.HIGH,
                                        null,
                                        null,
                                        null,
                                        "Rationale suficientemente longa."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankRationaleFails() {
        assertThatThrownBy(
                        () ->
                                CustomerConfidenceAssessment.newAssessment(
                                        tenant,
                                        meeting,
                                        account,
                                        50,
                                        ConfidenceBand.MEDIUM,
                                        null,
                                        null,
                                        null,
                                        "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
