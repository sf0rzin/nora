package br.com.nora.api.application.meeting;

import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.domain.meeting.Transcript;
import br.com.nora.api.infrastructure.nlp.SplitDtos;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Preview de separacao de um arquivo .txt com varias reunioes concatenadas (split). Apenas
 * orquestra a chamada ao worker {@code /split} e devolve as fronteiras propostas — NAO cria
 * reuniao, NAO persiste nada. A tela de confirmacao e o fatiamento real sao client-side.
 */
@Service
public class TranscriptSplitService {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptSplitService.class);

    private final NlpWorkerClient worker;

    public TranscriptSplitService(NlpWorkerClient worker) {
        this.worker = worker;
    }

    /**
     * @param tenantId tenant do chamador (JWT) — usado apenas para log/observabilidade; o preview
     *     nao toca dados do tenant.
     * @param transcript conteudo do .txt (ja validado pelo controller: formato, tamanho).
     * @param language ISO (ex: "pt-BR"); null/blank vira default.
     */
    public SplitDtos.SplitResponse preview(UUID tenantId, String transcript, String language) {
        if (transcript == null || transcript.isBlank()) {
            throw new MeetingException.EmptyTranscript();
        }
        if (transcript.length() > Transcript.MAX_CHAR_COUNT) {
            throw new MeetingException.TranscriptTooLarge(Transcript.MAX_CHAR_COUNT);
        }
        LOG.debug("split-preview: tenant={} transcript={} chars", tenantId, transcript.length());
        String lang = language == null || language.isBlank() ? "pt-BR" : language;
        return worker.split(transcript, lang);
    }
}
