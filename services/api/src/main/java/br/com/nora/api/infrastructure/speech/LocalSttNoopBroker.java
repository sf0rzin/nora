package br.com.nora.api.infrastructure.speech;

import br.com.nora.api.application.speech.SpeechException;
import br.com.nora.api.application.speech.SpeechToken;
import br.com.nora.api.application.speech.ports.SpeechTokenBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Adaptador no-op da porta {@code SpeechTokenBroker} para o STT local (ADR 0035 — Whisper embarcado
 * no Tauri, transcrevendo na máquina do cliente; sucessor do ADR 0009).
 *
 * <p>ESTADO FINAL vs. ESTE PASSO. O ADR 0035 §"Impacto no código" manda apagar {@code
 * SpeechController}, {@code SpeechTokenService}, {@code AzureSpeechTokenBroker}, o rate limit e o
 * bloco {@code nora.speech.*} inteiro. Esta classe NÃO contradiz isso: é o passo transitório que
 * torna aquela deleção segura. Enquanto houver desktop antigo em campo chamando {@code
 * /speech/token}, a rota precisa responder algo interpretável; quando não houver, some tudo junto.
 *
 * <p>POR QUE A PORTA CONTINUA EXISTINDO NESTE PASSO, em vez de apagar broker, service, controller e
 * DTO de uma vez:
 *
 * <ol>
 *   <li><b>Clientes antigos em campo.</b> O desktop já publicado chama {@code POST /speech/token}
 *       no boot da captura de voz. Sumir com a rota devolveria 404 genérico do Spring, que o
 *       cliente antigo não distingue de "API fora do ar" e transforma em retry infinito. Com a
 *       porta viva, a mesma rota responde 410 GONE + {@code SPEECH_PROVIDER_GONE} — sinal terminal
 *       e legível, que o cliente novo usa para cair direto no Whisper local.
 *   <li><b>Reversibilidade da migração.</b> {@code nora.speech.provider=azure} recoloca o {@code
 *       AzureSpeechTokenBroker} sem tocar em código. Se o Whisper local não performar no hardware
 *       de algum usuário, o rollback é uma env var, não um revert.
 *   <li><b>A porta não custa nada.</b> É uma interface de um método na camada de aplicação; o
 *       acoplamento com a Azure estava todo no adaptador de infraestrutura, que é exatamente o que
 *       este arquivo substitui. Apagar a porta junto seria jogar fora a abstração que tornou a
 *       troca barata.
 * </ol>
 *
 * <p>A deleção definitiva de {@code /speech/token} (e do resto do bloco, como manda o ADR 0035)
 * fica para depois que a telemetria mostrar zero chamada por uma janela de retenção inteira.
 *
 * <p>Nota sobre o rate limit: o {@code SpeechTokenService} consome o {@code SpeechRateLimiter}
 * ANTES de chamar o broker. Isso é intencional — um cliente antigo em loop de retry continua
 * limitado a 6 req/min por usuário, e o 410 não vira um endpoint gratuito de martelar.
 */
@Component
@ConditionalOnProperty(name = "nora.speech.provider", havingValue = "local", matchIfMissing = true)
public class LocalSttNoopBroker implements SpeechTokenBroker {

    private static final Logger LOG = LoggerFactory.getLogger(LocalSttNoopBroker.class);

    @Override
    public SpeechToken issue(String region) {
        LOG.debug(
                "Speech: /speech/token chamado com provider=local (region={}) — respondendo 410;"
                        + " cliente deve usar STT local.",
                region);
        throw new SpeechException.ProviderGone();
    }
}
