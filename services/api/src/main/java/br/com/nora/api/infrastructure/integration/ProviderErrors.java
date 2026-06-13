package br.com.nora.api.infrastructure.integration;

import br.com.nora.api.application.integration.IntegrationException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Conversão padrão de falha HTTP de provedor em {@code ProviderError} PT-BR: status + trecho do
 * corpo (≤300 chars), padrão do {@link GoogleWorkspaceClient}. Compartilhado pelos clients da onda
 * 1 (GitHub, Notion, Todoist, Linear) — os corpos de erro desses provedores são acionáveis e não
 * ecoam tokens.
 */
final class ProviderErrors {

    private static final int MAX_BODY_CHARS = 300;

    private ProviderErrors() {}

    /**
     * @param provider wire name do provedor (ex.: {@code github})
     * @param api rótulo da chamada que falhou (ex.: {@code issues})
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
