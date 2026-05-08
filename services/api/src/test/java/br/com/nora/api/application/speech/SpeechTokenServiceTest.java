package br.com.nora.api.application.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.speech.ports.SpeechTokenBroker;
import br.com.nora.api.infrastructure.speech.SpeechProperties;
import br.com.nora.api.infrastructure.speech.SpeechRateLimiter;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SpeechTokenServiceTest {

    private SpeechTokenBroker broker;
    private SpeechRateLimiter rateLimiter;
    private SpeechProperties props;
    private Clock clock;
    private SpeechTokenService service;

    @BeforeEach
    void setUp() {
        broker = mock(SpeechTokenBroker.class);
        rateLimiter = mock(SpeechRateLimiter.class);
        clock = mock(Clock.class);
        when(clock.now()).thenReturn(Instant.parse("2026-05-08T12:00:00Z"));

        props =
                new SpeechProperties(
                        new SpeechProperties.Azure(
                                "test-key",
                                "brazilsouth",
                                "https://%s.api.cognitive.microsoft.com/sts/v1.0/issueToken",
                                540,
                                5000),
                        new SpeechProperties.RateLimit(6));

        service = new SpeechTokenService(broker, rateLimiter, props, clock);
    }

    @Test
    void shouldIssueTokenWithDefaultRegion() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        SpeechToken expected =
                new SpeechToken("fake-token", "brazilsouth", Instant.now().plusSeconds(540));

        when(rateLimiter.tryConsume(userId)).thenReturn(true);
        when(broker.issue("brazilsouth")).thenReturn(expected);

        SpeechToken result = service.issueFor(userId, tenantId, null);

        assertThat(result).isEqualTo(expected);
        verify(broker).issue("brazilsouth");
    }

    @Test
    void shouldIssueTokenWithProvidedRegion() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        SpeechToken expected =
                new SpeechToken("fake-token", "eastus", Instant.now().plusSeconds(540));

        when(rateLimiter.tryConsume(userId)).thenReturn(true);
        when(broker.issue("eastus")).thenReturn(expected);

        SpeechToken result = service.issueFor(userId, tenantId, "eastus");

        assertThat(result).isEqualTo(expected);
        verify(broker).issue("eastus");
    }

    @Test
    void shouldRejectInvalidRegion() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        when(rateLimiter.tryConsume(userId)).thenReturn(true);

        assertThatThrownBy(() -> service.issueFor(userId, tenantId, "invalid-region"))
                .isInstanceOf(SpeechException.InvalidRegion.class)
                .hasMessageContaining("invalid-region");
    }

    @Test
    void shouldRejectWhenRateLimitExceeded() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        when(rateLimiter.tryConsume(userId)).thenReturn(false);

        assertThatThrownBy(() -> service.issueFor(userId, tenantId, null))
                .isInstanceOf(SpeechException.RateLimitExceeded.class);
    }

    @Test
    void shouldTrimAndNormalizeRegion() {
        UUID userId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        SpeechToken expected =
                new SpeechToken("fake-token", "brazilsouth", Instant.now().plusSeconds(540));

        when(rateLimiter.tryConsume(userId)).thenReturn(true);
        when(broker.issue("brazilsouth")).thenReturn(expected);

        SpeechToken result = service.issueFor(userId, tenantId, "  BrazilSouth  ");

        assertThat(result).isEqualTo(expected);
        verify(broker).issue("brazilsouth");
    }
}
