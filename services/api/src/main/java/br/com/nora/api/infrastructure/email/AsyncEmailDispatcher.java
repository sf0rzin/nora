package br.com.nora.api.infrastructure.email;

import br.com.nora.api.application.ports.EmailDispatcher;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs transactional e-mail sends on the shared async pool, after the current transaction commits.
 *
 * <p>Reuses the {@code taskExecutor} declared in {@link
 * br.com.nora.api.infrastructure.config.AsyncConfig}, whose task decorator carries the tenant
 * context across the thread hop and clears it afterwards. A private executor here would drop that
 * context and any write on the pool thread would be fail-closed under RLS.
 *
 * <p>{@code afterCommit}, not {@code afterCompletion}: a rolled back transaction announces nothing,
 * because the link inside the message would point at a token that was never persisted.
 *
 * <p>Delivery stays best effort, exactly as it was when the send sat inline — a saturated pool or a
 * provider failure is logged and the user flow carries on. The token is already in the database and
 * the user can ask for a new message.
 */
@Component
public class AsyncEmailDispatcher implements EmailDispatcher {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncEmailDispatcher.class);

    private final Executor executor;

    public AsyncEmailDispatcher(@Qualifier("taskExecutor") Executor executor) {
        this.executor = executor;
    }

    @Override
    public void dispatchAfterCommit(Runnable send) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            submit(send);
                        }
                    });
            return;
        }
        submit(send);
    }

    private void submit(Runnable send) {
        try {
            executor.execute(
                    () -> {
                        try {
                            send.run();
                        } catch (RuntimeException ex) {
                            LOG.error("Transactional e-mail send failed", ex);
                        }
                    });
        } catch (RejectedExecutionException ex) {
            LOG.error("Transactional e-mail rejected by the executor (pool saturated)", ex);
        }
    }
}
