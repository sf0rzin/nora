package br.com.nora.api.application.ports;

import java.time.Instant;

/** Porta para fonte de tempo. Permite testar logica de expiracao com Clock fake. */
public interface Clock {

    Instant now();
}
