package br.com.nora.api.application.ports;

import java.time.Instant;

/** Port for the time source. Lets expiration logic be tested with a fake Clock. */
public interface Clock {

    Instant now();
}
