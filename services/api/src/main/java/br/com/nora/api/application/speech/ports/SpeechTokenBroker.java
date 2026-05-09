package br.com.nora.api.application.speech.ports;

import br.com.nora.api.application.speech.SpeechToken;

public interface SpeechTokenBroker {
    SpeechToken issue(String region);
}
