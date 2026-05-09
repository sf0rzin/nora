package br.com.nora.api.infrastructure.nlp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuracao do cliente HTTP do NLP worker. Lido em application.yml -> nora.worker.* */
@ConfigurationProperties(prefix = "nora.worker")
public class NlpWorkerProperties {

    private String baseUrl = "http://localhost:8001";

    /** Timeout total da chamada /analyze, em milissegundos. */
    private long timeoutMillis = 120_000L;

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
}
