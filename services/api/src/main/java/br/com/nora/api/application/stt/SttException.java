package br.com.nora.api.application.stt;

/**
 * Failures of the realtime STT session path. {@code code} is the contract the desktop switches on
 * — see {@code packages/shared-contracts/error-codes.md}.
 *
 * <p>None of these messages may ever carry the provider key or the minted client secret: they are
 * serialized into the HTTP error body.
 */
public class SttException extends RuntimeException {

    private final String code;

    public SttException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    /**
     * The caller's per-user session budget is exhausted. Distinct from {@code RATE_LIMITED}, which
     * the auth path emits — a client that retries a login is doing something different from a
     * client that is opening transcription sessions in a loop.
     */
    public static class RateLimited extends SttException {
        public RateLimited() {
            super(
                    "STT_RATE_LIMITED",
                    "Too many transcription sessions were opened. Try again in a minute.");
        }
    }

    /**
     * No provider credential is configured on this deployment. Fail-visible on purpose: silently
     * answering "no session" would show up on the client as transcription that never starts, with
     * nothing naming the cause.
     */
    public static class NotConfigured extends SttException {
        public NotConfigured() {
            super(
                    "STT_NOT_CONFIGURED",
                    "Realtime transcription is not configured on this deployment.");
        }
    }

    /** The provider refused or could not be reached. */
    public static class BrokerError extends SttException {
        public BrokerError(String message) {
            super("STT_BROKER_ERROR", message);
        }
    }
}
