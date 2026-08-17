package br.com.nora.api.application.trends;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TopicNormalizerTest {

    @Test
    void caseAccentsAndPaddingAllFoldToTheSameKey() {
        String expected = "precificacao";

        assertThat(TopicNormalizer.fold("Precificação")).isEqualTo(expected);
        assertThat(TopicNormalizer.fold("  PRECIFICAÇÃO  ")).isEqualTo(expected);
        assertThat(TopicNormalizer.fold("precificacao")).isEqualTo(expected);
    }

    @Test
    void punctuationBecomesASingleSpaceAndDigitsSurvive() {
        assertThat(TopicNormalizer.fold("Renovação - Q4/2026")).isEqualTo("renovacao q4 2026");
        assertThat(TopicNormalizer.fold("churn,  risco")).isEqualTo("churn risco");
    }

    @Test
    void nothingCountableFoldsToTheEmptyKey() {
        assertThat(TopicNormalizer.fold(null)).isEmpty();
        assertThat(TopicNormalizer.fold("   ")).isEmpty();
        assertThat(TopicNormalizer.fold("---")).isEmpty();
    }

    /**
     * The documented limit of the ranking, pinned so nobody later reads the panel as a clustering.
     * Two words for the same subject stay two rows: merging them is a semantic judgement, and this
     * is a string function.
     */
    @Test
    void synonymsAreNotMerged() {
        assertThat(TopicNormalizer.fold("preço"))
                .isNotEqualTo(TopicNormalizer.fold("precificação"));
    }
}
