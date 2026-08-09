package br.com.nora.api.application.ports;

/**
 * Port for publishing domain events (e.g. {@code MeetingAnalysisCompletedEvent}).
 *
 * <p>Contract: if there is an active transaction at publish time, the event is only delivered to
 * listeners AFTER the commit (TransactionSynchronization). With no active transaction, delivery is
 * immediate. This guarantees that asynchronous listeners read the already-committed state from the
 * database.
 */
public interface DomainEventPublisher {

    void publish(Object event);
}
