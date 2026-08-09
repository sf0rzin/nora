package br.com.nora.api.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables scheduling ({@code @Scheduled}) — used by the LGPD retention sweeper (ADR 0029). */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
