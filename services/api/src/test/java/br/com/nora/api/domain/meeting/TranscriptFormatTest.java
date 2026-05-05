package br.com.nora.api.domain.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TranscriptFormatTest {

    @Test
    void parsesValidValues() {
        assertThat(TranscriptFormat.fromString("txt")).isEqualTo(TranscriptFormat.TXT);
        assertThat(TranscriptFormat.fromString(" VTT ")).isEqualTo(TranscriptFormat.VTT);
        assertThat(TranscriptFormat.fromString("srt")).isEqualTo(TranscriptFormat.SRT);
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> TranscriptFormat.fromString("docx"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TranscriptFormat.fromString(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
