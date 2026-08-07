package br.com.nora.api.infrastructure.speech;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config do broker de fala.
 *
 * <p>{@code provider} escolhe o adaptador da porta {@code SpeechTokenBroker}:
 *
 * <ul>
 *   <li>{@code local} (default) — STT roda no cliente (Whisper local no desktop). O broker responde
 *       410 GONE; nenhuma credencial de nuvem é necessária.
 *   <li>{@code azure} — comportamento anterior (STS regional da Azure). Preservado só para a janela
 *       de transição, enquanto houver cliente desktop antigo em campo.
 * </ul>
 *
 * <p>O bloco {@code azure} continua sendo lido mesmo com {@code provider=local} porque {@code
 * azure.default-region} e {@code rate-limit} alimentam o {@code SpeechTokenService} antes de chegar
 * no broker — o rate limit vale para os dois providers.
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
        // Defaults defensivos: com provider=local o bloco azure vira supérfluo e pode ser
        // removido do yml no futuro. Sem isso, o SpeechTokenService estouraria NPE (500) em
        // vez do 410 GONE que o contrato promete.
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
