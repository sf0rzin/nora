package br.com.nora.api.domain.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeetingTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID owner = UUID.randomUUID();

    @Test
    void newPendingTrimsTitleAndNormalizesDefaults() {
        Meeting m =
                Meeting.newPending(
                        tenant,
                        owner,
                        "  Discovery  ",
                        null,
                        null,
                        null,
                        TranscriptFormat.TXT,
                        List.of(),
                        List.of("Discovery", "discovery", " RENOVACAO "));

        assertThat(m.title()).isEqualTo("Discovery");
        assertThat(m.language()).isEqualTo("pt-BR");
        assertThat(m.processingStatus()).isEqualTo(ProcessingStatus.PENDING);
        assertThat(m.tags()).containsExactly("discovery", "renovacao");
        assertThat(m.id()).isNotNull();
        assertThat(m.durationSeconds()).isNull();
    }

    @Test
    void durationSecondsComputedWhenStartAndEndPresent() {
        OffsetDateTime start = OffsetDateTime.parse("2026-05-01T10:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-05-01T10:30:00Z");
        Meeting m =
                Meeting.newPending(
                        tenant,
                        owner,
                        "x",
                        start,
                        end,
                        "pt-BR",
                        TranscriptFormat.TXT,
                        List.of(),
                        List.of());
        assertThat(m.durationSeconds()).isEqualTo(1800L);
    }

    @Test
    void rejectsBlankTitle() {
        assertThatThrownBy(
                        () ->
                                Meeting.newPending(
                                        tenant,
                                        owner,
                                        "   ",
                                        null,
                                        null,
                                        null,
                                        TranscriptFormat.TXT,
                                        List.of(),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title");
    }

    @Test
    void rejectsEndBeforeStart() {
        OffsetDateTime start = OffsetDateTime.parse("2026-05-01T10:00:00Z");
        OffsetDateTime end = OffsetDateTime.parse("2026-05-01T09:00:00Z");
        assertThatThrownBy(
                        () ->
                                Meeting.newPending(
                                        tenant,
                                        owner,
                                        "x",
                                        start,
                                        end,
                                        null,
                                        TranscriptFormat.TXT,
                                        List.of(),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullTenantOrOwner() {
        assertThatThrownBy(
                        () ->
                                Meeting.newPending(
                                        null,
                                        owner,
                                        "t",
                                        null,
                                        null,
                                        null,
                                        TranscriptFormat.TXT,
                                        List.of(),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                Meeting.newPending(
                                        tenant,
                                        null,
                                        "t",
                                        null,
                                        null,
                                        null,
                                        TranscriptFormat.TXT,
                                        List.of(),
                                        List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
