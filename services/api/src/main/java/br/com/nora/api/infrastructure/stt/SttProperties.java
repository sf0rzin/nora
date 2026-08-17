package br.com.nora.api.infrastructure.stt;

import java.util.Locale;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Realtime STT configuration (ADR 0039, ADR 0045). Replaces the deleted {@code nora.speech} block.
 *
 * <p>There is no {@code provider} switch here, and that absence is deliberate. {@code
 * nora.speech.provider} existed to keep a rollback lever between two live vendors; there is one
 * realtime STT provider and nothing to roll back to, so a one-value switch would be a mechanism
 * that decides nothing — the same reasoning that collapses {@code SttBackendKind} on the desktop
 * side (ADR 0045 §5).
 *
 * <p>Every nested block has a defensive default because a null one would surface as a 500 on the
 * session endpoint instead of the {@code STT_NOT_CONFIGURED} the contract promises.
 */
@ConfigurationProperties(prefix = "nora.stt")
public record SttProperties(String defaultLanguage, OpenAi openai, RateLimit rateLimit) {

    static final String DEFAULT_LANGUAGE = "pt";
    static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    static final String DEFAULT_REALTIME_URL =
            "wss://api.openai.com/v1/realtime?intent=transcription";
    static final String DEFAULT_MODEL = "gpt-live-transcribe";
    static final String DEFAULT_AUDIO_FORMAT = "audio/pcm";
    static final int DEFAULT_SAMPLE_RATE = 24_000;

    /**
     * None, because {@link #DEFAULT_MODEL} refuses turn detection: the provider answers {@code 400
     * Turn detection is not supported for this transcription model} and no session is ever minted.
     * The two defaults have to agree, and the model is the half ADR 0045 fixes.
     */
    static final String DEFAULT_TURN_DETECTION = "";

    static final int DEFAULT_TTL_SECONDS = 600;
    static final int DEFAULT_TIMEOUT_MS = 5_000;
    static final int DEFAULT_SESSIONS_PER_MINUTE = 12;

    public SttProperties {
        defaultLanguage =
                (defaultLanguage == null || defaultLanguage.isBlank())
                        ? DEFAULT_LANGUAGE
                        : defaultLanguage.trim().toLowerCase(Locale.ROOT);
        openai = openai == null ? new OpenAi(null, null, null, null, null, 0, null, 0, 0) : openai;
        rateLimit = rateLimit == null ? new RateLimit(0) : rateLimit;
    }

    /**
     * OpenAI realtime transcription settings.
     *
     * <p>{@code model}, {@code realtimeUrl} and {@code audioFormat} are configuration rather than
     * constants on purpose: this provider surface has been renamed before, and every one of these
     * values is echoed to the desktop in the session response, so a rename is an environment
     * variable instead of a desktop release.
     *
     * @param apiKey the account key. It stays in this process — never in a response, never in a
     *     log, never in the desktop binary (ADR 0039 §Decision 3)
     * @param baseUrl REST base used to mint the session credential
     * @param realtimeUrl WebSocket URL handed to the client
     * @param model transcription model requested when the session is created
     * @param audioFormat provider media type of the PCM the client streams
     * @param sampleRate input sample rate in Hz. The desktop capture pipeline targets the same
     *     number ({@code stt::TARGET_SAMPLE_RATE}); changing one without the other feeds the
     *     provider audio at the wrong speed, which transcribes as gibberish rather than failing
     * @param turnDetection provider VAD mode that segments the stream into utterances. Empty sends
     *     no turn detection at all, which is the DEFAULT and the only value {@link #DEFAULT_MODEL}
     *     accepts — that model transcribes continuously and emits its own transcription
     *     delta/completed events, so nothing is left waiting for a commit
     * @param sessionTtlSeconds requested credential lifetime. The provider clamps it (10..7200 at
     *     the time of writing) and the value governs how long the secret can OPEN a connection, not
     *     how long an open session lives (ADR 0045 §3)
     * @param requestTimeoutMs budget for the mint call. Short on purpose: it sits between the user
     *     pressing record and the recording starting
     */
    public record OpenAi(
            String apiKey,
            String baseUrl,
            String realtimeUrl,
            String model,
            String audioFormat,
            int sampleRate,
            String turnDetection,
            int sessionTtlSeconds,
            int requestTimeoutMs) {

        public OpenAi {
            baseUrl = trimTrailingSlash(orDefault(baseUrl, DEFAULT_BASE_URL));
            realtimeUrl = orDefault(realtimeUrl, DEFAULT_REALTIME_URL);
            model = orDefault(model, DEFAULT_MODEL);
            audioFormat = orDefault(audioFormat, DEFAULT_AUDIO_FORMAT);
            sampleRate = sampleRate > 0 ? sampleRate : DEFAULT_SAMPLE_RATE;
            turnDetection = turnDetection == null ? DEFAULT_TURN_DETECTION : turnDetection.trim();
            sessionTtlSeconds = sessionTtlSeconds > 0 ? sessionTtlSeconds : DEFAULT_TTL_SECONDS;
            requestTimeoutMs = requestTimeoutMs > 0 ? requestTimeoutMs : DEFAULT_TIMEOUT_MS;
            // 'unset' is the sentinel a secret store writes when it cannot hold an empty value;
            // HttpEmbeddingClient already treats it as absent, and so must this.
            apiKey = (apiKey == null || "unset".equals(apiKey.trim())) ? "" : apiKey.trim();
        }

        public boolean isConfigured() {
            return !apiKey.isBlank();
        }

        private static String orDefault(String value, String fallback) {
            return (value == null || value.isBlank()) ? fallback : value.trim();
        }

        private static String trimTrailingSlash(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    /**
     * Per-user budget of minted sessions.
     *
     * <p>The default is 12 a minute, not the 6 the Azure broker used, and the reason is measurable:
     * {@code commands.rs} starts ONE session per track, so a recording with system audio on opens
     * two at once, and a dropped connection mid-meeting costs another one each time it reconnects.
     * At 6 a minute a user who restarts a recording twice while the network is flapping is refused
     * by their own rate limiter.
     */
    public record RateLimit(int sessionsPerMinute) {

        public RateLimit {
            sessionsPerMinute =
                    sessionsPerMinute > 0 ? sessionsPerMinute : DEFAULT_SESSIONS_PER_MINUTE;
        }
    }
}
