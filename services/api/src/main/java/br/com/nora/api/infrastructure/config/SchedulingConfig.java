package br.com.nora.api.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduling ({@code @Scheduled}) — used by the LGPD retention sweeper (ADR 0029) and by
 * the stuck-analysis sweeper that releases meetings abandoned in PROCESSING.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
