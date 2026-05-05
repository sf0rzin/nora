package br.com.nora.api.infrastructure.security;

import br.com.nora.api.application.ports.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class SystemClock implements Clock {

    @Override
    public Instant now() {
        return Instant.now();
    }
}
