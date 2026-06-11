package br.com.nora.api.application.workflow;

import java.util.Map;

/**
 * Porta das ações estilo MCP do NORA Flows. Cada adapter executa UM tipo de ação (ex.: {@code
 * send_email} via Resend; futuramente {@code gmail_send}, {@code calendar_create_event}, {@code
 * slack_post} via OAuth do usuário).
 *
 * <p>Contrato: sucesso retorna uma mensagem curta PT-BR para o log da execução ("E-mail enviado
 * para x@y.z"). Falha DEVE propagar exceção — o {@link WorkflowEngine} captura, registra no log e
 * marca a execução como FAILED. Nunca finja sucesso.
 */
public interface ActionExecutor {

    /** Identificador do tipo de ação no definition_json (ex.: {@code send_email}). */
    String type();

    String execute(WorkflowEventContext context, Map<String, Object> params);
}
