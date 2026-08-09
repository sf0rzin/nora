package br.com.nora.api.infrastructure.speech;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Speech broker config.
 *
 * <p>{@code provider} selects the adapter for the {@code SpeechTokenBroker} port:
 *
 * <ul>
 *   <li>{@code local} (default) — STT runs on the client (local Whisper on the desktop). The broker
 *       answers 410 GONE; no cloud credential is needed.
 *   <li>{@code azure} — previous behavior (Azure regional STS). Preserved only for the transition
 *       window, while there are still old desktop clients in the field.
 * </ul>
 *
 * <p>The {@code azure} block is still read even with {@code provider=local} because {@code
 * azure.default-region} and {@code rate-limit} feed the {@code SpeechTokenService} before reaching
 * the broker — the rate limit applies to both providers.
 */
@ConfigurationProperties(prefix = "nora.speech")
public record SpeechProperties(String provider, Azure azure, RateLimit rateLimit) {

    public static final String PROVIDER_LOCAL = "local";
    public static final String PROVIDER_AZURE = "azure";

    private static final Azure DEFAULT_AZURE =
            new Azure(
                    "",
                    "brazilsouth",
                    "https://%s.api.cognitive.microsoft.com/sts/v1.0/issueToken",
                    540,
                    5000);
    private static final RateLimit DEFAULT_RATE_LIMIT = new RateLimit(6);

    public SpeechProperties {
        provider =
                (provider == null || provider.isBlank())
                        ? PROVIDER_LOCAL
                        : provider.trim().toLowerCase(Locale.ROOT);
        // Defensive defaults: with provider=local the azure block becomes superfluous and may be
        // removed from the yml in the future. Without this, SpeechTokenService would blow up with
        // an NPE (500) instead of the 410 GONE the contract promises.
        azure = azure == null ? DEFAULT_AZURE : azure;
        rateLimit = rateLimit == null ? DEFAULT_RATE_LIMIT : rateLimit;
    }

    public boolean isLocal() {
        return PROVIDER_LOCAL.equals(provider);
    }

    public record Azure(
            String subscriptionKey,
            String defaultRegion,
            String tokenEndpointTemplate,
            int tokenTtlSeconds,
            int requestTimeoutMs) {}

    public record RateLimit(int tokensPerMinute) {}
}
