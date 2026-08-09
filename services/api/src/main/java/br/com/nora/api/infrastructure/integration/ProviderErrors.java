package br.com.nora.api.infrastructure.integration;

import br.com.nora.api.application.integration.IntegrationException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Standard conversion of a provider HTTP failure into a PT-BR {@code ProviderError}: status + body
 * excerpt (≤300 chars), the {@link GoogleWorkspaceClient} pattern. Shared by the wave 1 clients
 * (GitHub, Notion, Todoist, Linear) — these providers' error bodies are actionable and do not echo
 * tokens.
 */
final class ProviderErrors {

    private static final int MAX_BODY_CHARS = 300;

    private ProviderErrors() {}

    /**
     * @param provider provider wire name (e.g. {@code github})
     * @param api label of the call that failed (e.g. {@code issues})
     */
    static IntegrationException of(String provider, String api, Exception ex) {
        if (ex instanceof IntegrationException ie) {
            return ie;
        }
        String reason;
        if (ex instanceof WebClientResponseException http) {
            String detail = http.getResponseBodyAsString();
            reason =
                    http.getStatusCode().value()
                            + (detail.isBlank()
                                    ? ""
                                    : " — "
                                            + detail.substring(
                                                    0, Math.min(detail.length(), MAX_BODY_CHARS)));
        } else {
            reason = ex.getMessage();
        }
        return new IntegrationException.ProviderError(provider, api + ": " + reason);
    }
}
