package br.com.nora.api.application.ports;

/**
 * Gera embeddings (vetores) de texto pra busca semântica do chat (RAG). Provider-agnóstico (ADR
 * 0004): a implementação fala com Gemini (default) ou OpenAI conforme config. Todos os vetores de
 * um ambiente usam o MESMO provider+modelo (espaços vetoriais não são intercambiáveis).
 */
public interface EmbeddingClient {

    /**
     * Vetor do texto. Lança {@link EmbeddingException} em falha (caller trata como best-effort).
     */
    float[] embed(String text);

    /**
     * Identificador {@code provider:model} do espaço vetorial — gravado junto pra comparar só
     * iguais.
     */
    String modelId();

    /** Se há credencial configurada pro provider ativo. Falso = embeddings desligados (no-op). */
    boolean isEnabled();

    /** Falha ao gerar embedding (rede, credencial, resposta inesperada do provider). */
    class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }

        public EmbeddingException(String message) {
            super(message);
        }
    }
}
