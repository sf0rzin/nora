package br.com.nora.api.infrastructure.nlp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the NLP worker HTTP client. Read from application.yml -> nora.worker.* */
@ConfigurationProperties(prefix = "nora.worker")
public class NlpWorkerProperties {

    private String baseUrl = "http://localhost:8001";

    /**
     * Total timeout for the /analyze call, in milliseconds.
     *
     * <p>The worker gives up before this on purpose. Its {@code LLM_REQUEST_BUDGET_SECONDS} (90s by
     * default) is a wall-clock budget across every retry and every window of {@code /split},
     * deliberately under this deadline: without it a single call could spend 360s — three SDK
     * attempts, then the whole prompt again in JSON mode — and go on burning paid tokens for four
     * minutes after this client had already stopped waiting for the answer. Raising this number
     * without raising the worker's budget only lengthens the wait; lowering it below 90s makes the
     * worker's own ceiling unreachable and puts the abandoned-call behaviour back.
     */
    private long timeoutMillis = 120_000L;

    /**
     * Shared secret sent as {@code X-Internal-Token} on every worker call, so that reaching {@code
     * worker:8001} is not enough to spend an LLM call (ADR 0023 §3-4, same shape as {@code
     * InternalTokenAuthFilter} but in the opposite direction).
     *
     * <p>Distinct from {@code nora.platform.internal-token}, which authenticates worker/BFF
     * <em>into</em> this API. Blank means the header is not sent at all — the worker then decides,
     * and its own default is to refuse with 503 unless {@code NORA_WORKER_ALLOW_UNAUTHENTICATED} is
     * set. Blank is not a way to bypass the worker's gate.
     */
    private String internalToken = "";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }
}
