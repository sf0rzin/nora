package br.com.nora.api.application.integration;

/** Business-rule exceptions of the "IntegrationConnection" aggregate (integrations hub). */
public abstract class IntegrationException extends RuntimeException {

    private final String code;

    protected IntegrationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final class NotConnected extends IntegrationException {
        public NotConnected(String provider) {
            super(
                    "INTEGRATION_NOT_CONNECTED",
                    "Integração '" + provider + "' não está conectada — conecte em Integrações");
        }
    }

    public static final class InvalidState extends IntegrationException {
        public InvalidState() {
            super("INTEGRATION_INVALID_STATE", "state do OAuth inválido ou expirado");
        }
    }

    public static final class UnknownProvider extends IntegrationException {
        public UnknownProvider(String provider) {
            super("INTEGRATION_UNKNOWN_PROVIDER", "provedor desconhecido: " + provider);
        }
    }

    public static final class NotConfigured extends IntegrationException {
        public NotConfigured(String provider) {
            super(
                    "INTEGRATION_NOT_CONFIGURED",
                    "Integração '"
                            + provider
                            + "' não está configurada no servidor (faltam credenciais OAuth)");
        }
    }

    /**
     * Telegram pairing not completed yet: the backend did not find the user's {@code /start
     * <código>} in getUpdates. It is NOT a provider failure — the hub tells the user to try again.
     */
    public static final class PairingPending extends IntegrationException {
        public PairingPending(String message) {
            super("INTEGRATION_PAIRING_PENDING", message);
        }
    }

    public static final class ProviderError extends IntegrationException {
        public ProviderError(String provider, String detail) {
            super("INTEGRATION_PROVIDER_ERROR", "Erro do provedor '" + provider + "': " + detail);
        }
    }
}
