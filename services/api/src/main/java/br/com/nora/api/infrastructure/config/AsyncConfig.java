package br.com.nora.api.infrastructure.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configura o {@link Executor} usado por {@link org.springframework.scheduling.annotation.Async}.
 *
 * <p>Sem este bean, Spring cai em {@code SimpleAsyncTaskExecutor} (deprecated) que cria UMA thread
 * por chamada — cada upload de meeting spawnava thread nova fazendo HTTP blocking ao worker. N
 * uploads paralelos = N threads + N conexões Postgres (pool max=10) → exaustão de file descriptors
 * e starvation do pool.
 *
 * <p>Pool config:
 *
 * <ul>
 *   <li>core=2: economiza recurso quando idle
 *   <li>max=8: combina com Container App scale-out (max 3 réplicas × 8 = 24 análises paralelas)
 *   <li>queue=50: absorve picos sem rejeitar; depois disso joga {@link
 *       java.util.concurrent.RejectedExecutionException} (caller trata e marca o meeting como
 *       FAILED em vez de PROCESSING órfão)
 * </ul>
 */
@Configuration
@EnableAsync
public class AsyncConfig implements AsyncConfigurer {

    @Override
    @Bean(name = "taskExecutor")
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("nora-async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
