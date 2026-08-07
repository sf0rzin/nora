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
     * O provider de fala em nuvem foi desativado (STT passou a rodar no cliente). Mapeado para 410
     * GONE — sinal TERMINAL, ao contrario do 500/502 que um broker quebrado produziria e que o
     * cliente antigo trataria como falha transitoria digna de retry.
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
