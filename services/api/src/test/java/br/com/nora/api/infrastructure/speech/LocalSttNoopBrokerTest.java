package br.com.nora.api.infrastructure.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.speech.SpeechException;
import br.com.nora.api.application.speech.SpeechTokenService;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalSttNoopBrokerTest {

    private final LocalSttNoopBroker broker = new LocalSttNoopBroker();

    private static SpeechProperties localProps() {
        return new SpeechProperties(
                SpeechProperties.PROVIDER_LOCAL,
                new SpeechProperties.Azure(
                        "",
                        "brazilsouth",
                        "https://%s.api.cognitive.microsoft.com/sts/v1.0/issueToken",
                        540,
                        5000),
                new SpeechProperties.RateLimit(6));
    }

    @Test
    void shouldRefuseWithTerminalProviderGoneCode() {
        Throwable thrown = catchThrowable(() -> broker.issue("brazilsouth"));

        assertThat(thrown)
                .isInstanceOf(SpeechException.ProviderGone.class)
                .hasMessageContaining("local");
        // The code is the contract with the client: it is what GlobalExceptionHandler turns into
        // a 410.
        assertThat(((SpeechException) thrown).code()).isEqualTo("SPEECH_PROVIDER_GONE");
    }

    @Test
    void shouldRefuseForEveryRegion() {
        for (String region : new String[] {"brazilsouth", "eastus", "westeurope"}) {
            assertThatThrownBy(() -> broker.issue(region))
                    .isInstanceOf(SpeechException.ProviderGone.class);
        }
    }

    @Test
    void shouldStillGoThroughRateLimiterBeforeRefusing() {
        SpeechRateLimiter rateLimiter = mock(SpeechRateLimiter.class);
        Clock clock = mock(Clock.class);
        UUID userId = UUID.randomUUID();
        when(rateLimiter.tryConsume(userId)).thenReturn(true);

        SpeechTokenService service =
                new SpeechTokenService(broker, rateLimiter, localProps(), clock);

        assertThatThrownBy(() -> service.issueFor(userId, UUID.randomUUID(), null))
                .isInstanceOf(SpeechException.ProviderGone.class);

        verify(rateLimiter).tryConsume(userId);
    }

    @Test
    void shouldPreferRateLimitOverProviderGone() {
        SpeechRateLimiter rateLimiter = mock(SpeechRateLimiter.class);
        Clock clock = mock(Clock.class);
        UUID userId = UUID.randomUUID();
        when(rateLimiter.tryConsume(userId)).thenReturn(false);

        SpeechTokenService service =
                new SpeechTokenService(broker, rateLimiter, localProps(), clock);

        // 429 before 410: the rate limit protects the endpoint even after the provider is gone.
        assertThatThrownBy(() -> service.issueFor(userId, UUID.randomUUID(), null))
                .isInstanceOf(SpeechException.RateLimitExceeded.class);
    }

    @Test
    void shouldDefaultProviderToLocalWhenUnset() {
        SpeechProperties props =
                new SpeechProperties(
                        null,
                        new SpeechProperties.Azure("", "brazilsouth", "%s", 540, 5000),
                        new SpeechProperties.RateLimit(6));

        assertThat(props.provider()).isEqualTo(SpeechProperties.PROVIDER_LOCAL);
        assertThat(props.isLocal()).isTrue();
    }
}
