package br.com.nora.api.infrastructure.speech;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.speech.SpeechException;
import br.com.nora.api.application.speech.SpeechToken;
import br.com.nora.api.application.speech.ports.SpeechTokenBroker;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Adaptador legado: troca a subscription key pelo token de curta duracao do STS regional da Azure.
 *
 * <p>Fora do caminho default desde a saida do Azure — o default e {@code LocalSttNoopBroker}
 * ({@code nora.speech.provider=local}). Mantido, e nao deletado, para que o rollback da migracao
 * de STT seja uma env var ({@code NORA_SPEECH_PROVIDER=azure}) e nao um revert de codigo.
 */
@Component
@ConditionalOnProperty(name = "nora.speech.provider", havingValue = "azure")
public class AzureSpeechTokenBroker implements SpeechTokenBroker {

    private final SpeechProperties props;
    private final RestClient restClient;
    private final Clock clock;

    public AzureSpeechTokenBroker(
            SpeechProperties props, RestClient.Builder restClientBuilder, Clock clock) {
        this.props = props;
        this.restClient =
                restClientBuilder
                        .requestFactory(
                                new org.springframework.http.client
                                        .SimpleClientHttpRequestFactory())
                        .build();
        this.clock = clock;
    }

    @Override
    public SpeechToken issue(String region) {
        String url = String.format(props.azure().tokenEndpointTemplate(), region);

        String token =
                restClient
                        .post()
                        .uri(url)
                        .header("Ocp-Apim-Subscription-Key", props.azure().subscriptionKey())
                        .header("Content-Length", "0")
                        .retrieve()
                        .onStatus(
                                HttpStatusCode::isError,
                                (req, res) -> {
                                    throw new SpeechException.BrokerError(
                                            "Azure STS returned " + res.getStatusCode());
                                })
                        .body(String.class);

        if (token == null || token.isBlank()) {
            throw new SpeechException.BrokerError("Azure STS returned empty token");
        }

        return new SpeechToken(
                token, region, clock.now().plusSeconds(props.azure().tokenTtlSeconds()));
    }
}
