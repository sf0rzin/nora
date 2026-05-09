package br.com.nora.api.application.speech;

import java.time.Instant;

public record SpeechToken(String token, String region, Instant expiresAt) {}
