package br.com.nora.api.infrastructure.platform;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** No-op recorder (platform off): does nothing, never throws — fail-soft for AnalysisService. */
class NoOpUsageRecorderTest {

    private final NoOpUsageRecorder recorder = new NoOpUsageRecorder();

    @Test
    void recordAnalysisUsage_noOp() {
        assertThatCode(
                        () ->
                                recorder.recordAnalysisUsage(
                                        UUID.randomUUID(), "openai-gpt-4o-mini", 10, 5, 100, false))
                .doesNotThrowAnyException();
    }

    @Test
    void recordExternal_noOp() {
        assertThatCode(
                        () ->
                                recorder.recordExternal(
                                        "chat",
                                        "openai",
                                        "gpt-4o-mini",
                                        UUID.randomUUID(),
                                        10,
                                        5,
                                        BigDecimal.ONE,
                                        100,
                                        "ok"))
                .doesNotThrowAnyException();
    }
}
