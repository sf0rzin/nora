package br.com.nora.api.infrastructure.platform;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Estado de disponibilidade do control plane (ADR 0022). Sempre presente (não-gated): quando a
 * plataforma está desabilitada fica {@code DISABLED}; quando habilitada começa {@code DEGRADED} e
 * vira {@code HEALTHY} após a migração Flyway bem-sucedida; se a migração falhar, permanece {@code
 * DEGRADED}. Admin endpoints exigem {@code HEALTHY}; o hot-path (llm-config/usage) faz
 * fallback/drop quando não está usable.
 */
@Component
public class PlatformAvailability {

    public enum State {
        DISABLED,
        DEGRADED,
        HEALTHY
    }

    private final AtomicReference<State> state;

    public PlatformAvailability(PlatformProperties props) {
        this.state = new AtomicReference<>(props.isEnabled() ? State.DEGRADED : State.DISABLED);
    }

    public void markHealthy() {
        state.set(State.HEALTHY);
    }

    public void markDegraded() {
        state.updateAndGet(s -> s == State.DISABLED ? State.DISABLED : State.DEGRADED);
    }

    /**
     * True só quando habilitada E migrada — única condição em que o banco de plataforma é usável.
     */
    public boolean isUsable() {
        return state.get() == State.HEALTHY;
    }

    public State state() {
        return state.get();
    }
}
