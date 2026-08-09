package br.com.nora.api.infrastructure.platform;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * Availability state of the control plane (ADR 0022). Always present (not gated): when the platform
 * is disabled it stays {@code DISABLED}; when enabled it starts {@code DEGRADED} and becomes {@code
 * HEALTHY} after a successful Flyway migration; if the migration fails, it stays {@code DEGRADED}.
 * Admin endpoints require {@code HEALTHY}; the hot path (llm-config/usage) falls back/drops when it
 * is not usable.
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
     * True only when enabled AND migrated — the only condition in which the platform database is
     * usable.
     */
    public boolean isUsable() {
        return state.get() == State.HEALTHY;
    }

    public State state() {
        return state.get();
    }
}
