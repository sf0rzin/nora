package br.com.nora.api.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduling ({@code @Scheduled}) — used by the LGPD retention sweeper (ADR 0029), by the
 * stuck-analysis sweeper that releases meetings abandoned in PROCESSING, and by {@code
 * ScheduledFlowRunner}, the dispatcher of the {@code schedule.cron} Flows trigger (ADR 0047).
 *
 * <p>The three share Spring's default single-threaded scheduler, so they never overlap each other.
 * That is a convenience, not the guarantee any of them rests on: the Flows runner claims each run
 * in the database, because "one container" is a deployment fact and not a property of the code.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {}
