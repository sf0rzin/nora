package br.com.nora.api.infrastructure.events;

import br.com.nora.api.application.ports.DomainEventPublisher;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Adapter do event bus in-process sobre o {@link ApplicationEventPublisher} do Spring.
 *
 * <p>Semântica pós-commit: com transação ativa no ponto de publicação, o evento é segurado e
 * entregue só no {@code afterCommit} (rollback = evento descartado — listener nunca vê estado que
 * não foi commitado). Sem transação ativa (ex.: {@code AnalysisService.run()}, que comita status em
 * transações curtas e publica fora delas), a entrega é imediata — o estado relevante já está no
 * banco.
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
