package br.com.nora.api.infrastructure.config;

import br.com.nora.api.infrastructure.security.TenantContextHolder;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configures the {@link Executor} used by {@link org.springframework.scheduling.annotation.Async}.
 *
 * <p>Without this bean, Spring falls back to {@code SimpleAsyncTaskExecutor} (deprecated) which
 * creates ONE thread per call — each meeting upload spawned a new thread doing blocking HTTP to the
 * worker. N parallel uploads = N threads + N Postgres connections (pool max=10) → file descriptor
 * exhaustion and pool starvation.
 *
 * <p>Pool config:
 *
 * <ul>
 *   <li>core=2: saves resources when idle
 *   <li>max=8: matches Container App scale-out (max 3 replicas × 8 = 24 parallel analyses)
 *   <li>queue=50: absorbs spikes without rejecting; past that it throws {@link
 *       java.util.concurrent.RejectedExecutionException} (the caller handles it and marks the
 *       meeting as FAILED instead of an orphaned PROCESSING)
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
        // Propagates the tenant (TenantContextHolder) from the request thread to the executor
        // thread. Under RLS enforce (ADR 0028) the async analysis pipeline writes to enforced
        // tables (meeting_analyses + children) — TenantRlsAspect needs the GUC, which comes from
        // this holder. Without this, the task runs with no tenant on the pool thread and the
        // writes would be fail-closed. We capture at submit (request, holder set by
        // JwtAuthenticationFilter) and clear at the end so it does not leak between tasks that
        // reuse the thread.
        executor.setTaskDecorator(
                runnable -> {
                    UUID tenantId = TenantContextHolder.get();
                    return () -> {
                        if (tenantId != null) {
                            TenantContextHolder.set(tenantId);
                        }
                        try {
                            runnable.run();
                        } finally {
                            TenantContextHolder.clear();
                        }
                    };
                });
        executor.initialize();
        return executor;
    }
}
