package br.com.nora.api.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Liga o agendamento ({@code @Scheduled}) — usado pelo sweeper de retenção LGPD (ADR 0029). */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
