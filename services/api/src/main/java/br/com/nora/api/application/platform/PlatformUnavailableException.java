package br.com.nora.api.application.platform;

/**
 * Signals that the control plane is unavailable (platform database disabled or in degraded mode).
 * Mapped to 503 on the /admin/platform/* endpoints. It is NEVER thrown on the hot path
 * (/internal/platform/llm-config does a SOFT fallback; /usage drops silently).
 */
public class PlatformUnavailableException extends RuntimeException {
    public PlatformUnavailableException(String message) {
        super(message);
    }
}
