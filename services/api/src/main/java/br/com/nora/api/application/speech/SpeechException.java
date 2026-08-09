package br.com.nora.api.application.speech;

public class SpeechException extends RuntimeException {

    private final String code;

    public SpeechException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static class RateLimitExceeded extends SpeechException {
        public RateLimitExceeded() {
            super("RATE_LIMIT_EXCEEDED", "Speech token rate limit exceeded. Try again later.");
        }
    }

    public static class InvalidRegion extends SpeechException {
        public InvalidRegion(String region) {
            super("INVALID_REGION", "Region not allowed: " + region);
        }
    }

    public static class BrokerError extends SpeechException {
        public BrokerError(String message) {
            super("BROKER_ERROR", message);
        }
    }

    /**
     * The cloud speech provider was decommissioned (STT now runs on the client). Mapped to 410 GONE
     * — a TERMINAL signal, unlike the 500/502 a broken broker would produce and that the old client
     * would treat as a transient failure worth retrying.
     */
    public static class ProviderGone extends SpeechException {
        public ProviderGone() {
            super(
                    "SPEECH_PROVIDER_GONE",
                    "Cloud speech tokens are no longer issued. This client must use local"
                            + " on-device speech-to-text.");
        }
    }
}
