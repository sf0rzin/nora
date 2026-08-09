package br.com.nora.api.infrastructure.events;

import br.com.nora.api.application.ports.DomainEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * In-process event bus adapter over Spring's {@link ApplicationEventPublisher}.
 *
 * <p>Post-commit semantics: with an active transaction at the publication point, the event is held
 * and delivered only in {@code afterCommit} (rollback = event discarded — the listener never sees
 * state that was not committed). With no active transaction (e.g. {@code AnalysisService.run()},
 * which commits status in short transactions and publishes outside them), delivery is immediate —
 * the relevant state is already in the database.
 */
@Component
public class SpringDomainEventPublisher implements DomainEventPublisher {

    private final ApplicationEventPublisher delegate;

    public SpringDomainEventPublisher(ApplicationEventPublisher delegate) {
        this.delegate = delegate;
    }

    @Override
    public void publish(Object event) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            delegate.publishEvent(event);
                        }
                    });
        } else {
            delegate.publishEvent(event);
        }
    }
}
