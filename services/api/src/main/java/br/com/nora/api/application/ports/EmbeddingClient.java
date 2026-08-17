package br.com.nora.api.application.ports;

/**
 * Generates text embeddings (vectors) for the chat's semantic search (RAG). Provider-agnostic (ADR
 * 0004): the implementation talks to Gemini (default) or OpenAI depending on config. All vectors in
 * an environment use the SAME provider+model (vector spaces are not interchangeable).
 */
public interface EmbeddingClient {

    /**
     * Vector for the text. Throws {@link EmbeddingException} on failure (the caller treats it as
     * best-effort).
     */
    float[] embed(String text);

    /**
     * Same call as {@link #embed}, plus whatever the provider reported about the input it billed.
     * The default delegates to {@link #embed} and reports zero tokens, so a client that cannot
     * observe usage does not have to implement anything.
     */
    default Embedding embedWithUsage(String text) {
        return new Embedding(embed(text), 0);
    }

    /**
     * {@code provider:model} identifier of the vector space — stored alongside so only matching
     * ones are compared.
     */
    String modelId();

    /**
     * Whether there is a credential configured for the active provider. False = embeddings off
     * (no-op).
     */
    boolean isEnabled();

    /**
     * A vector plus the input tokens the provider charged for it. {@code promptTokens} is 0 when
     * the provider did not report a count — Gemini's {@code embedContent} returns none — so 0 reads
     * as unknown, never as free. The cost telemetry of ADR 0024 records it as-is rather than
     * estimating a number the provider never sent.
     */
    record Embedding(float[] vector, int promptTokens) {}

    /** Failure generating an embedding (network, credential, unexpected provider response). */
    class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }

        public EmbeddingException(String message) {
            super(message);
        }
    }
}
