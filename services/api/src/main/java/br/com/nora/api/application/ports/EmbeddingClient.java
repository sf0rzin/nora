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
     * {@code provider:model} identifier of the vector space — stored alongside so only matching
     * ones are compared.
     */
    String modelId();

    /**
     * Whether there is a credential configured for the active provider. False = embeddings off
     * (no-op).
     */
    boolean isEnabled();

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
