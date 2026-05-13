package br.com.nora.api.application.analysis;

import br.com.nora.api.application.ports.NlpWorkerClient;
import br.com.nora.api.infrastructure.nlp.WorkerDtos;
import br.com.nora.api.infrastructure.nlp.WorkerDtos.LiveHighlights;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LiveAnalysisService {

    private static final Logger LOG = LoggerFactory.getLogger(LiveAnalysisService.class);

    private final NlpWorkerClient worker;

    public LiveAnalysisService(NlpWorkerClient worker) {
        this.worker = worker;
    }

    public WorkerDtos.LiveAnalyzeResponse analyze(
            String transcriptChunk, String language, LiveHighlights previousHighlights) {
        LOG.debug("live-analyze: chunk={} chars", transcriptChunk.length());
        return worker.analyzeLive(transcriptChunk, language, previousHighlights);
    }
}
