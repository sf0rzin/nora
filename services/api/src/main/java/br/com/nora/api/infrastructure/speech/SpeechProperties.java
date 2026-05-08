package br.com.nora.api.infrastructure.speech;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nora.speech")
public record SpeechProperties(
        Azure azure,
        RateLimit rateLimit) {

    public record Azure(
            String subscriptionKey,
            String defaultRegion,
            String tokenEndpointTemplate,
            int tokenTtlSeconds,
            int requestTimeoutMs) {}

    public record RateLimit(
            int tokensPerMinute) {}
}
