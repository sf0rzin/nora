package br.com.nora.api.infrastructure.nlp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the NLP worker HTTP client. Read from application.yml -> nora.worker.* */
@ConfigurationProperties(prefix = "nora.worker")
public class NlpWorkerProperties {

    private String baseUrl = "http://localhost:8001";

    /** Total timeout for the /analyze call, in milliseconds. */
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
