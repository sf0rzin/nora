package br.com.nora.api.application.speech;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.speech.ports.SpeechTokenBroker;
import br.com.nora.api.infrastructure.speech.SpeechProperties;
import br.com.nora.api.infrastructure.speech.SpeechRateLimiter;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class SpeechTokenService {

    private final SpeechTokenBroker broker;
    private final SpeechRateLimiter rateLimiter;
    private final SpeechProperties props;
    private final Clock clock;

    public SpeechTokenService(
            SpeechTokenBroker broker,
            SpeechRateLimiter rateLimiter,
            SpeechProperties props,
            Clock clock) {
        this.broker = broker;
        this.rateLimiter = rateLimiter;
        this.props = props;
        this.clock = clock;
    }

    public SpeechToken issueFor(UUID userId, UUID tenantId, String regionOrNull) {
        if (!rateLimiter.tryConsume(userId)) {
            throw new SpeechException.RateLimitExceeded();
        }

        String region =
                (regionOrNull == null || regionOrNull.isBlank())
                        ? props.azure().defaultRegion()
                        : regionOrNull.trim().toLowerCase();

        SpeechRegions.requireAllowed(region);

        return broker.issue(region);
    }
}
