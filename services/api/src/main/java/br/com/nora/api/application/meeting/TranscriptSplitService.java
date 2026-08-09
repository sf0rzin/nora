package br.com.nora.api.application.meeting;

import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.domain.meeting.Transcript;
import br.com.nora.api.infrastructure.nlp.SplitDtos;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Split preview of a .txt file with several concatenated meetings (split). It only orchestrates the
 * call to the {@code /split} worker and returns the proposed boundaries — it does NOT create a
 * meeting, it does NOT persist anything. The confirmation screen and the real slicing are
 * client-side.
 */
@Service
public class TranscriptSplitService {

    private static final Logger LOG = LoggerFactory.getLogger(TranscriptSplitService.class);

    private final NlpWorkerClient worker;

    public TranscriptSplitService(NlpWorkerClient worker) {
        this.worker = worker;
    }

    /**
     * @param tenantId caller's tenant (JWT) — used only for logging/observability; the preview does
     *     not touch tenant data.
     * @param transcript content of the .txt (already validated by the controller: format, size).
     * @param language ISO (e.g. "pt-BR"); null/blank falls back to the default.
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
