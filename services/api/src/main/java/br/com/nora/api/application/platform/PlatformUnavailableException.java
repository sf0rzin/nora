package br.com.nora.api.application.platform;

/**
 * Sinaliza que o control plane está indisponível (banco de plataforma desabilitado ou em modo
 * degradado). Mapeada para 503 nos endpoints de /admin/platform/*. NUNCA é lançada no hot-path
 * (/internal/platform/llm-config faz fallback SOFT; /usage descarta silenciosamente).
 */
public class PlatformUnavailableException extends RuntimeException {
    public PlatformUnavailableException(String message) {
        super(message);
    }
}
