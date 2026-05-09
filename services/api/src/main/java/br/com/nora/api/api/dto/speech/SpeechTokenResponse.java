package br.com.nora.api.api.dto.speech;

import java.time.Instant;

public record SpeechTokenResponse(String token, String region, Instant expiresAt) {}
