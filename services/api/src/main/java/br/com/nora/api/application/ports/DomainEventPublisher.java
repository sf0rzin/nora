package br.com.nora.api.application.ports;

/**
 * Porta para publicação de eventos de domínio (ex.: {@code MeetingAnalysisCompletedEvent}).
 *
 * <p>Contrato: se houver transação ativa no momento da publicação, o evento só é entregue aos
 * listeners APÓS o commit (TransactionSynchronization). Sem transação ativa, a entrega é imediata.
 * Isso garante que listeners assíncronos leiam do banco o estado já commitado.
 */
public interface DomainEventPublisher {

    void publish(Object event);
}
